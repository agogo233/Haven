//! RDP Server Redirection Packet parser — MS-RDPBCGR §2.2.13.1.1
//! (`RDP_SERVER_REDIRECTION_PACKET`).
//!
//! GNOME Remote Desktop (and Windows RDS load-balancers) send a server
//! redirection PDU to hand a connecting client off to another endpoint /
//! session. Haven follows it by reconnecting, injecting the
//! `LoadBalanceInfo` routing token as the X.224 negotiation cookie
//! (`Cookie: msts=…`) and using the redirected target/credentials when the
//! server supplies them. Without this, GRD's redirect is ignored, the
//! original transport times out, and the user sees a black screen (#117).
//!
//! Field layout, wire order, and little-endian encoding cross-referenced
//! against FreeRDP `libfreerdp/core/redirection.c`. Flag values are the
//! MS-RDPBCGR §2.2.13.1.1 `LB_*` constants.
//!
//! NOTE: this module parses the redirection *packet body* (from the `Flags`
//! UINT16 onward) and is unit-tested in isolation. Locating the packet
//! within a live Share Control frame, and the reconnect wiring, are pending
//! end-to-end verification against a real GDM/GRD server (see #117).

// RedirFlags (LB_*) — MS-RDPBCGR §2.2.13.1.1, little-endian UINT32 bitmask.
const LB_TARGET_NET_ADDRESS: u32 = 0x0000_0001;
const LB_LOAD_BALANCE_INFO: u32 = 0x0000_0002;
const LB_USERNAME: u32 = 0x0000_0004;
const LB_DOMAIN: u32 = 0x0000_0008;
const LB_PASSWORD: u32 = 0x0000_0010;
#[allow(dead_code)]
const LB_DONTSTOREUSERNAME: u32 = 0x0000_0020;
#[allow(dead_code)]
const LB_SMARTCARD_LOGON: u32 = 0x0000_0040;
const LB_NOREDIRECT: u32 = 0x0000_0080;
const LB_TARGET_FQDN: u32 = 0x0000_0100;
const LB_TARGET_NETBIOS_NAME: u32 = 0x0000_0200;
const LB_TARGET_NET_ADDRESSES: u32 = 0x0000_0800;
const LB_CLIENT_TSV_URL: u32 = 0x0000_1000;
#[allow(dead_code)]
const LB_SERVER_TSV_CAPABLE: u32 = 0x0000_2000;
#[allow(dead_code)]
const LB_PASSWORD_IS_PK_ENCRYPTED: u32 = 0x0000_4000;
const LB_REDIRECTION_GUID: u32 = 0x0000_8000;
const LB_TARGET_CERTIFICATE: u32 = 0x0001_0000;

/// Bits we know how to skip/consume. A redirection packet whose RedirFlags
/// contains bits outside this mask is treated as unparseable (we'd lose
/// cursor alignment on the unknown field).
const KNOWN_FLAGS: u32 = LB_TARGET_NET_ADDRESS
    | LB_LOAD_BALANCE_INFO
    | LB_USERNAME
    | LB_DOMAIN
    | LB_PASSWORD
    | LB_DONTSTOREUSERNAME
    | LB_SMARTCARD_LOGON
    | LB_NOREDIRECT
    | LB_TARGET_FQDN
    | LB_TARGET_NETBIOS_NAME
    | LB_TARGET_NET_ADDRESSES
    | LB_CLIENT_TSV_URL
    | LB_SERVER_TSV_CAPABLE
    | LB_PASSWORD_IS_PK_ENCRYPTED
    | LB_REDIRECTION_GUID
    | LB_TARGET_CERTIFICATE;

/// Parsed server-redirection instructions. Only the fields Haven needs to
/// follow a redirect are surfaced; other present fields are consumed (to
/// keep the cursor aligned) but discarded.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct RedirectionInfo {
    pub session_id: u32,
    pub redir_flags: u32,
    /// Explicit target (IP/host) to reconnect to, if the server supplied one.
    pub target_net_address: Option<String>,
    pub target_fqdn: Option<String>,
    /// Raw routing token — replayed as the X.224 nego cookie on reconnect.
    pub load_balance_info: Option<Vec<u8>>,
    pub username: Option<String>,
    pub domain: Option<String>,
    pub password: Option<Vec<u8>>,
    /// LB_NOREDIRECT: server says "do not redirect" (use the current connection).
    pub no_redirect: bool,
}

impl RedirectionInfo {
    /// The host to reconnect to: the explicit target net address, else the
    /// target FQDN, else `None` meaning "reconnect to the same endpoint"
    /// (GRD's same-host session handover).
    pub fn target_host(&self) -> Option<String> {
        self.target_net_address
            .clone()
            .filter(|s| !s.is_empty())
            .or_else(|| self.target_fqdn.clone().filter(|s| !s.is_empty()))
    }
}

/// Minimal little-endian cursor that returns `None` on a short read rather
/// than panicking — untrusted server bytes must never crash the reader.
struct Cursor<'a> {
    buf: &'a [u8],
    pos: usize,
}

impl<'a> Cursor<'a> {
    fn new(buf: &'a [u8]) -> Self {
        Self { buf, pos: 0 }
    }
    fn remaining(&self) -> usize {
        self.buf.len().saturating_sub(self.pos)
    }
    fn read_u16(&mut self) -> Option<u16> {
        let s = self.take(2)?;
        Some(u16::from_le_bytes([s[0], s[1]]))
    }
    fn read_u32(&mut self) -> Option<u32> {
        let s = self.take(4)?;
        Some(u32::from_le_bytes([s[0], s[1], s[2], s[3]]))
    }
    fn take(&mut self, n: usize) -> Option<&'a [u8]> {
        if self.remaining() < n {
            return None;
        }
        let s = &self.buf[self.pos..self.pos + n];
        self.pos += n;
        Some(s)
    }
    /// A UINT32-length-prefixed blob (the encoding of every optional field).
    fn read_len_blob(&mut self) -> Option<&'a [u8]> {
        let len = self.read_u32()? as usize;
        self.take(len)
    }
}

/// Decode a UTF-16LE string field, stripping any trailing NUL terminator.
fn utf16le(bytes: &[u8]) -> String {
    let units: Vec<u16> = bytes
        .chunks_exact(2)
        .map(|p| u16::from_le_bytes([p[0], p[1]]))
        .collect();
    String::from_utf16_lossy(&units)
        .trim_end_matches('\0')
        .to_string()
}

/// Parse an `RDP_SERVER_REDIRECTION_PACKET` body, starting at the leading
/// `Flags` UINT16. Returns `None` if the bytes are too short, carry unknown
/// RedirFlags bits, or a flagged field is truncated.
pub fn parse_redirection_packet(packet: &[u8]) -> Option<RedirectionInfo> {
    let mut c = Cursor::new(packet);
    let _flags = c.read_u16()?; // SEC_REDIRECTION_PKT (0x0400) for std-sec; not validated.
    let _length = c.read_u16()?; // overall length; we bound on the buffer instead.
    let session_id = c.read_u32()?;
    let redir = c.read_u32()?;

    // Refuse packets with flags we don't know how to consume — parsing past
    // an unknown variable-length field would desync the cursor and produce
    // garbage. Better to decline than to mis-parse.
    if redir & !KNOWN_FLAGS != 0 {
        return None;
    }

    let mut info = RedirectionInfo {
        session_id,
        redir_flags: redir,
        no_redirect: redir & LB_NOREDIRECT != 0,
        ..Default::default()
    };

    // Optional fields in wire order (FreeRDP redirection.c). Each present
    // field is a UINT32 length followed by that many bytes; a truncated
    // field aborts the parse (`?`).
    if redir & LB_TARGET_NET_ADDRESS != 0 {
        info.target_net_address = Some(utf16le(c.read_len_blob()?));
    }
    if redir & LB_LOAD_BALANCE_INFO != 0 {
        info.load_balance_info = Some(c.read_len_blob()?.to_vec());
    }
    if redir & LB_USERNAME != 0 {
        info.username = Some(utf16le(c.read_len_blob()?));
    }
    if redir & LB_DOMAIN != 0 {
        info.domain = Some(utf16le(c.read_len_blob()?));
    }
    if redir & LB_PASSWORD != 0 {
        info.password = Some(c.read_len_blob()?.to_vec());
    }
    if redir & LB_TARGET_FQDN != 0 {
        info.target_fqdn = Some(utf16le(c.read_len_blob()?));
    }
    if redir & LB_TARGET_NETBIOS_NAME != 0 {
        c.read_len_blob()?; // not needed for reconnect
    }
    if redir & LB_CLIENT_TSV_URL != 0 {
        c.read_len_blob()?;
    }
    if redir & LB_REDIRECTION_GUID != 0 {
        c.read_len_blob()?;
    }
    if redir & LB_TARGET_CERTIFICATE != 0 {
        c.read_len_blob()?;
    }
    if redir & LB_TARGET_NET_ADDRESSES != 0 {
        // UINT32 length covering [addressCount + Unicode address strings].
        c.read_len_blob()?;
    }

    Some(info)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Build a redirection packet body: Flags, Length(placeholder),
    /// SessionID, RedirFlags, then the already-encoded optional fields.
    fn pkt(session_id: u32, redir: u32, fields: &[u8]) -> Vec<u8> {
        let mut v = Vec::new();
        v.extend_from_slice(&0x0400u16.to_le_bytes()); // Flags = SEC_REDIRECTION_PKT
        v.extend_from_slice(&0u16.to_le_bytes()); // Length placeholder
        v.extend_from_slice(&session_id.to_le_bytes());
        v.extend_from_slice(&redir.to_le_bytes());
        v.extend_from_slice(fields);
        // backfill total length
        let len = v.len() as u16;
        v[2..4].copy_from_slice(&len.to_le_bytes());
        v
    }

    fn len_blob(bytes: &[u8]) -> Vec<u8> {
        let mut v = (bytes.len() as u32).to_le_bytes().to_vec();
        v.extend_from_slice(bytes);
        v
    }

    fn utf16le_bytes(s: &str) -> Vec<u8> {
        let mut v: Vec<u8> = s.encode_utf16().flat_map(|u| u.to_le_bytes()).collect();
        v.extend_from_slice(&0u16.to_le_bytes()); // NUL terminator
        v
    }

    #[test]
    fn empty_flags_parses_to_bare_info() {
        let p = pkt(7, 0, &[]);
        let info = parse_redirection_packet(&p).expect("should parse");
        assert_eq!(info.session_id, 7);
        assert_eq!(info.redir_flags, 0);
        assert!(info.target_host().is_none());
        assert!(info.load_balance_info.is_none());
        assert!(!info.no_redirect);
    }

    #[test]
    fn load_balance_info_only_is_same_host_handover() {
        // GRD's typical case: a routing token, no explicit target (reconnect
        // to the same endpoint carrying the cookie).
        let token = b"Cookie: msts=12345.67890.0000\r\n";
        let p = pkt(0x55, LB_LOAD_BALANCE_INFO, &len_blob(token));
        let info = parse_redirection_packet(&p).expect("should parse");
        assert_eq!(info.load_balance_info.as_deref(), Some(&token[..]));
        assert!(info.target_host().is_none(), "no target => same-host handover");
    }

    #[test]
    fn target_address_and_credentials_in_wire_order() {
        let mut fields = Vec::new();
        fields.extend_from_slice(&len_blob(&utf16le_bytes("192.168.0.42"))); // TARGET_NET_ADDRESS
        fields.extend_from_slice(&len_blob(b"\x01\x02\x03\x04")); // LOAD_BALANCE_INFO
        fields.extend_from_slice(&len_blob(&utf16le_bytes("ian"))); // USERNAME
        fields.extend_from_slice(&len_blob(&utf16le_bytes("WORKGROUP"))); // DOMAIN
        fields.extend_from_slice(&len_blob(&utf16le_bytes("FQDN.example"))); // TARGET_FQDN
        let flags = LB_TARGET_NET_ADDRESS | LB_LOAD_BALANCE_INFO | LB_USERNAME | LB_DOMAIN | LB_TARGET_FQDN;
        let p = pkt(1, flags, &fields);
        let info = parse_redirection_packet(&p).expect("should parse");
        assert_eq!(info.target_net_address.as_deref(), Some("192.168.0.42"));
        assert_eq!(info.target_host().as_deref(), Some("192.168.0.42")); // net addr wins over FQDN
        assert_eq!(info.load_balance_info.as_deref(), Some(&b"\x01\x02\x03\x04"[..]));
        assert_eq!(info.username.as_deref(), Some("ian"));
        assert_eq!(info.domain.as_deref(), Some("WORKGROUP"));
        assert_eq!(info.target_fqdn.as_deref(), Some("FQDN.example"));
    }

    #[test]
    fn fqdn_used_as_target_when_no_net_address() {
        let p = pkt(1, LB_TARGET_FQDN, &len_blob(&utf16le_bytes("host.lan")));
        let info = parse_redirection_packet(&p).expect("should parse");
        assert_eq!(info.target_host().as_deref(), Some("host.lan"));
    }

    #[test]
    fn no_redirect_flag_is_surfaced() {
        let p = pkt(1, LB_NOREDIRECT, &[]);
        let info = parse_redirection_packet(&p).expect("should parse");
        assert!(info.no_redirect);
    }

    #[test]
    fn unknown_flag_bit_is_declined() {
        // A bit outside KNOWN_FLAGS would desync the cursor — decline.
        let p = pkt(1, 0x8000_0000, &[]);
        assert!(parse_redirection_packet(&p).is_none());
    }

    #[test]
    fn truncated_field_returns_none_not_panic() {
        // Flag says LOAD_BALANCE_INFO present but the blob is cut short.
        let mut p = pkt(1, LB_LOAD_BALANCE_INFO, &[]);
        p.extend_from_slice(&100u32.to_le_bytes()); // claims 100 bytes, none follow
        assert!(parse_redirection_packet(&p).is_none());
    }

    #[test]
    fn short_header_returns_none() {
        assert!(parse_redirection_packet(&[0x00, 0x04]).is_none());
        assert!(parse_redirection_packet(&[]).is_none());
    }

    #[test]
    fn skipped_fields_keep_cursor_aligned() {
        // NETBIOS + TSV_URL + GUID + CERT + NET_ADDRESSES are consumed but
        // discarded; a trailing field we *do* want must still parse correctly.
        let mut fields = Vec::new();
        fields.extend_from_slice(&len_blob(b"\xaa\xbb")); // LOAD_BALANCE_INFO (wanted)
        fields.extend_from_slice(&len_blob(&utf16le_bytes("nb"))); // NETBIOS (skipped)
        fields.extend_from_slice(&len_blob(b"tsv-url-bytes")); // CLIENT_TSV_URL (skipped)
        fields.extend_from_slice(&len_blob(b"\x00\x11\x22\x33\x44\x55\x66\x77\x88\x99\xaa\xbb\xcc\xdd\xee\xff")); // GUID (skipped, 16B)
        let flags = LB_LOAD_BALANCE_INFO | LB_TARGET_NETBIOS_NAME | LB_CLIENT_TSV_URL | LB_REDIRECTION_GUID;
        let p = pkt(1, flags, &fields);
        let info = parse_redirection_packet(&p).expect("should parse");
        assert_eq!(info.load_balance_info.as_deref(), Some(&b"\xaa\xbb"[..]));
    }
}
