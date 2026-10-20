"""iOS 5.1.0 ARM64 null-header guard for WebViewObject.AddCustomHeader.

Evidence: the same crash family as the Android patch_webview51 (null header
key/value forwarded to the native webview plugin). In UnityFramework (IPA
5.1.0, dump.cs RVA 0x31C77D4) the method marshals headerKey/headerValue and
calls _CWebViewPlugin_AddCustomHeader unconditionally; a null managed string
marshals to NULL and the Objective-C plugin aborts on nil dictionary
key/value. The only guard is the IntPtr.op_Equality(webView, IntPtr.Zero)
call at 0x31C77FC. Replace that call sequence (mov x1,#0 / mov x2,#0 / bl
op_Equality / tbz w0,#0,continue) with an inlined equivalent plus null
checks:

    cbz x0,  return     ; webView == IntPtr.Zero (was op_Equality + tbz)
    cbz x20, return     ; headerKey   == null
    cbz x19, return     ; headerValue == null
    b   continue

Non-null headers, URLs, TLS and everything else are byte-identical. The
patch verifies the whole 0x88-byte function and the Mach-O layout first and
refuses on drift. Only the Mach-O bytes are changed; the IPA must be re-signed
afterwards.
"""
import hashlib
import struct

ENTRY_SUFFIX = 'Frameworks/UnityFramework.framework/UnityFramework'
FUNC = 0x31C77D4
FUNC_LEN = 0x88
RETURN = 0x31C7804
CONTINUE = 0x31C7814
FUNC_SHA256 = '21c29e9a617ab68cd927381b30c2537110628e17ea8059fda2401de746df05c4'
# movz x1,#0 / movz x2,#0 / bl IntPtr.op_Equality / tbz w0,#0,CONTINUE
OLD_SEQUENCE = bytes.fromhex('010080d2 020080d2 6b94f294 a0000036')
OLD_OFFSET = 0x31C77F4
FUNC_PREFIX = bytes.fromhex('f657bda9 f44f01a9 fd7b02a9 fd830091 f30302aa f40301aa f50300aa 004440f9')


def macho_file_offset(data, address, size=4):
    """Map a VM address inside a thin little-endian ARM64 Mach-O to a file offset."""
    if len(data) < 32 or struct.unpack_from('<I', data, 0)[0] != 0xFEEDFACF:
        raise ValueError('Expected a thin 64-bit little-endian Mach-O')
    cputype, = struct.unpack_from('<I', data, 4)
    if cputype != 0x0100000C:
        raise ValueError('Expected an ARM64 Mach-O')
    ncmds, = struct.unpack_from('<I', data, 16)
    pos = 32
    for _ in range(ncmds):
        cmd, cmdsize = struct.unpack_from('<II', data, pos)
        if cmd == 0x19:  # LC_SEGMENT_64
            segname = data[pos + 8:pos + 24].split(b'\0')[0]
            vmaddr, vmsize, fileoff, filesize = struct.unpack_from('<QQQQ', data, pos + 24)
            if segname == b'__TEXT' and vmaddr <= address and address + size <= vmaddr + filesize:
                result = fileoff + address - vmaddr
                if result + size <= len(data):
                    return result
        pos += cmdsize
    raise ValueError('Address is not inside the file-backed __TEXT segment')


def cbz(register, address, target):
    delta = target - address
    if not 0 <= register <= 30 or delta % 4 or not -(1 << 20) <= delta < (1 << 20):
        raise ValueError('Invalid CBZ operand')
    return struct.pack('<I', 0xB4000000 | (((delta // 4) & 0x7FFFF) << 5) | register)


def branch(address, target):
    delta = target - address
    if delta % 4 or not -(1 << 27) <= delta < (1 << 27):
        raise ValueError('Invalid B operand')
    return struct.pack('<I', 0x14000000 | ((delta // 4) & 0x3FFFFFF))


def new_sequence():
    return (cbz(0, OLD_OFFSET, RETURN) + cbz(20, OLD_OFFSET + 4, RETURN)
            + cbz(19, OLD_OFFSET + 8, RETURN) + branch(OLD_OFFSET + 12, CONTINUE))


def patch(data):
    """Return (patched_bytes, report); raises ValueError on any layout drift."""
    start = macho_file_offset(data, FUNC, FUNC_LEN)
    func = data[start:start + FUNC_LEN]
    if hashlib.sha256(func).hexdigest() != FUNC_SHA256 or not func.startswith(FUNC_PREFIX):
        raise ValueError('WebViewObject.AddCustomHeader bytes differ from 5.1.0; refusing patch')
    offset = macho_file_offset(data, OLD_OFFSET, len(OLD_SEQUENCE))
    if data[offset:offset + len(OLD_SEQUENCE)] != OLD_SEQUENCE:
        raise ValueError('Unexpected op_Equality guard sequence; refusing patch')
    replacement = new_sequence()
    patched = bytearray(data)
    patched[offset:offset + len(replacement)] = replacement
    report = {
        'entry_suffix': ENTRY_SUFFIX,
        'function': '%#x' % FUNC,
        'file_offset': offset,
        'old_bytes': OLD_SEQUENCE.hex(),
        'new_bytes': replacement.hex(),
        'guards': ['webView == IntPtr.Zero', 'headerKey == null', 'headerValue == null'],
        'return': '%#x' % RETURN,
        'continue': '%#x' % CONTINUE,
    }
    return bytes(patched), report
