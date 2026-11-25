#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Pack a swipe word list and bigram list (TSV, see build_dicts.py) into one compact .kpd file,
or repack existing .kpd v1/v2 dictionaries into flat mmap-able .kpd v2 ("DKPD").

    # Pack TSVs into v1 (default) or flat mmap-able v2 (--v2):
    pack_swipe_dict.py <lang>.words.tsv <lang>.bigrams.tsv out.kpd [--v2] [--min-bigram=N]
                       [--trigrams=<lang>.trigrams.tsv --min-trigram=N --trigram-bytes=B] [--total=T]

    # Repack an existing .kpd (v1 or v2) to v2 with optional word pruning and key-path aliases:
    pack_swipe_dict.py --repack-v2 <in.kpd> <out.kpd> [--prune=il,...] [--alias=il:I'll,wel:we'll,...]

    # Repack all *.kpd files in a directory to v2 (applying English junk pruning + contraction aliases):
    pack_swipe_dict.py --repack-all <in_dir> <out_dir>

The format is described in native/droneswipe/FORMAT.md; the native loader is
KpDictionary::load_kpd / load_kpd_fd in native/droneswipe/kp_dictionary.cpp.
"""
from collections import deque
import glob
import math
import os
import struct
import sys
import unicodedata

# Default English cleanup: prune junk "il" and add collapsed-double key-path aliases for 'll contractions.
DEFAULT_EN_PRUNE = ("il",)
DEFAULT_EN_ALIASES = {
    "il": "I'll",
    "wel": "we'll",
    "hel": "he'll",
    "shel": "she'll",
    "youl": "you'll",
    "theyl": "they'll",
}

FLAG_BFS_TRIE = 1 << 0
FLAG_TRIE_HAS_NON_AZ = 1 << 1
FLAG_HAS_FOOTER = 1 << 2
FOOTER_SIZE = 24


def fast_hash64(data: bytes) -> int:
    """Matches FastHash64 in native/droneswipe/kp_dictionary.cpp bit-for-bit."""
    m64 = 0xFFFFFFFFFFFFFFFF
    c0 = 0x9E3779B97F4A7C15
    c1 = 0xBF58476D1CE4E5B9
    c2 = 0x94D049BB133111EB
    c3 = 0xD6E8FEB86659FD93
    h0, h1, h2, h3 = c0, c1, c2, c3
    n = len(data)
    full = (n // 32) * 32
    for w0, w1, w2, w3 in struct.iter_unpack("<4Q", memoryview(data)[:full]):
        v0 = (h0 ^ ((w0 * c0) & m64))
        h0 = ((((v0 << 31) | (v0 >> 33)) & m64) * c1) & m64
        v1 = (h1 ^ ((w1 * c1) & m64))
        h1 = ((((v1 << 27) | (v1 >> 37)) & m64) * c2) & m64
        v2 = (h2 ^ ((w2 * c2) & m64))
        h2 = ((((v2 << 33) | (v2 >> 31)) & m64) * c3) & m64
        v3 = (h3 ^ ((w3 * c3) & m64))
        h3 = ((((v3 << 29) | (v3 >> 35)) & m64) * c0) & m64
    x = (
        h0
        ^ (((h1 << 13) | (h1 >> 51)) & m64)
        ^ (((h2 << 29) | (h2 >> 35)) & m64)
        ^ (((h3 << 47) | (h3 >> 17)) & m64)
        ^ n
    )
    for b in data[full:]:
        x ^= (b + c0 + ((x << 6) & m64) + (x >> 2)) & m64
        x = (x * c1) & m64
    x ^= x >> 30
    x = (x * c1) & m64
    x ^= x >> 27
    x = (x * c2) & m64
    x ^= x >> 31
    return x


def varint(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def read_varint(b, i):
    n = s = 0
    while True:
        x = b[i]
        i += 1
        n |= (x & 0x7F) << s
        s += 7
        if not (x & 0x80):
            return n, i


def front(prev, cur):
    pb, cb = prev.encode(), cur.encode()
    shared = 0
    while shared < min(len(pb), len(cb)) and pb[shared] == cb[shared]:
        shared += 1
    while shared and shared < len(cb) and (cb[shared] & 0xC0) == 0x80:
        shared -= 1  # never split a UTF-8 sequence
    return varint(shared) + varint(len(cb) - shared) + cb[shared:]


def native_lower_cp(cp):
    """Mirror of lower_cp() in kp_dictionary.cpp."""
    if 0x41 <= cp <= 0x5A:
        return cp + 32
    if 0xC0 <= cp <= 0xDE and cp != 0xD7:
        return cp + 32
    if 0x100 <= cp <= 0x17F:
        shifted = 0x139 <= cp <= 0x148 or 0x179 <= cp <= 0x17E
        if (cp % 2 == 1) if shifted else (cp % 2 == 0):
            return cp + 1
    return cp


def native_upper_cp(cp):
    """Mirror of upper_cp() in kp_dictionary.cpp (inverse of native_lower_cp)."""
    if 0x61 <= cp <= 0x7A:
        return cp - 32
    if 0xE0 <= cp <= 0xFE and cp != 0xF7:
        return cp - 32
    if 0x100 <= cp <= 0x17F:
        shifted = 0x139 <= cp <= 0x148 or 0x179 <= cp <= 0x17E
        if (cp % 2 == 0) if shifted else (cp % 2 == 1):
            return cp - 1
    return cp


def native_lower(s):
    return "".join(chr(native_lower_cp(ord(c))) for c in s)


def capitalized(low):
    return chr(native_upper_cp(ord(low[0]))) + low[1:] if low else low


def is_capitalized_surface(surface):
    """Mirror of is_capitalized_surface() in kp_dictionary.cpp."""
    if len(surface) <= 1:
        return False
    c0 = ord(surface[0])
    if surface[0] == "I" and surface[1] in ("'", "\u2019"):
        return False
    return native_lower_cp(c0) != c0


def read_kpd_data(path):
    """Read an existing .kpd (v1 'KPD1' or v2 'DKPD') into neutral in-memory structures.

    Returns dict with:
      lowers: list[str] (sorted by UTF-8 bytes)
      lower_logc_q: list[int] (u16, round(ln(count)*2048))
      variants: list[tuple[str, int, int, bool]]  (surface, freq_u8, lower_id, capitalized)
      kp_to_vids: dict[str, list[int]]
      bigrams: dict[int, dict[int, int]]          (prev_id -> {word_id: logc_u8})
      trigrams: dict[tuple[int,int], tuple[int, dict[int, int]]]  ((w1,w2) -> (ctx_q, {w3: q}))
      total: float
    """
    b = open(path, "rb").read()
    magic = b[:4]
    if magic == b"KPD1":
        ver, n_lower, n_variants, n_prevs, n_big, total, n_sec = struct.unpack_from("<IIIIIdI", b, 4)
        if ver != 2:
            raise SystemExit(f"{path}: unsupported KPD1 version {ver}")
        off = 4 + struct.calcsize("<IIIIIdI")
        sec = {}
        for _ in range(n_sec):
            tag = b[off:off + 4]
            o, ln = struct.unpack_from("<II", b, off + 4)
            sec[tag] = (o, ln)
            off += 12

        o, _ = sec[b"CMAP"]
        cmap = {}
        for k in range(struct.unpack_from("<I", b, o)[0]):
            cp, base = struct.unpack_from("<IB", b, o + 4 + 5 * k)
            cmap[cp] = chr(base)

        o, ln = sec[b"LOWR"]
        i, prev, lowers = o, b"", []
        while i < o + ln:
            sh, i = read_varint(b, i)
            n, i = read_varint(b, i)
            prev = prev[:sh] + b[i:i + n]
            i += n
            lowers.append(prev.decode("utf-8"))
        assert len(lowers) == n_lower

        o, _ = sec[b"LOGC"]
        lower_logc_q = list(struct.unpack_from(f"<{n_lower}H", b, o))

        def derive(low):
            return "".join(ch if "a" <= ch <= "z" else cmap.get(ord(ch), "") for ch in low)

        i, _ = sec[b"WREC"]
        fi, _ = sec[b"FREQ"]
        variants = []
        kp_to_vids = {}
        for wid, low in enumerate(lowers):
            spec, i = read_varint(b, i)
            kp = derive(low)
            if spec & 4:
                n, i = read_varint(b, i)
                kp = b[i:i + n].decode("utf-8")
                i += n
            surfs = ([low] if spec & 1 else []) + ([capitalized(low)] if spec & 2 else [])
            for _ in range(spec >> 3):
                n, i = read_varint(b, i)
                surfs.append(b[i:i + n].decode("utf-8"))
                i += n
            for s in surfs:
                fr = b[fi]
                fi += 1
                if kp:
                    vid = len(variants)
                    variants.append((s, fr, wid, is_capitalized_surface(s)))
                    kp_to_vids.setdefault(kp, []).append(vid)

        bigrams = {}
        if b"BIGR" in sec:
            i, _ = sec[b"BIGR"]
            p = 0
            for _ in range(n_prevs):
                d, i = read_varint(b, i)
                p += d
                n, i = read_varint(b, i)
                row = {}
                w = 0
                for _ in range(n):
                    dw, i = read_varint(b, i)
                    w += dw
                    row[w] = b[i]
                    i += 1
                if p < n_lower and row:
                    bigrams[p] = row

        trigrams = {}
        if b"TRIG" in sec:
            i, _ = sec[b"TRIG"]
            n_ctx, _ = struct.unpack_from("<II", b, i)
            i += 8
            w1 = w2 = 0
            for _ in range(n_ctx):
                d1, i = read_varint(b, i)
                w1 += d1
                v, i = read_varint(b, i)
                w2 = v if d1 else w2 + v
                ctx_q = b[i]
                i += 1
                n, i = read_varint(b, i)
                row = {}
                w = 0
                for _ in range(n):
                    dw, i = read_varint(b, i)
                    w += dw
                    row[w] = b[i]
                    i += 1
                if w1 < n_lower and w2 < n_lower and row:
                    trigrams[(w1, w2)] = (ctx_q, row)

        return {
            "lowers": lowers,
            "lower_logc_q": lower_logc_q,
            "variants": variants,
            "kp_to_vids": kp_to_vids,
            "bigrams": bigrams,
            "trigrams": trigrams,
            "total": total,
        }

    if magic == b"DKPD":
        ver, flags, n_lower, n_variants, n_kps, n_nodes, n_big, n_tri_ctx, n_tri, total, n_sec, _ = (
            struct.unpack_from("<9Id2I", b, 4)
        )
        if ver != 2:
            raise SystemExit(f"{path}: unsupported DKPD version {ver}")
        off = 56
        sec = {}
        for _ in range(n_sec):
            tag = b[off:off + 4]
            o, ln = struct.unpack_from("<II", b, off + 4)
            sec[tag] = (o, ln)
            off += 12

        lwo, _ = sec[b"LWOF"]
        lwp, _ = sec[b"LWPL"]
        l_offs = struct.unpack_from(f"<{n_lower + 1}I", b, lwo)
        lowers = [b[lwp + l_offs[i]:lwp + l_offs[i + 1] - 1].decode("utf-8") for i in range(n_lower)]
        lco, _ = sec[b"LOGC"]
        lower_logc_q = list(struct.unpack_from(f"<{n_lower}H", b, lco))

        vo, _ = sec[b"VARS"]
        vrp = sec[b"VRPL"][0] if b"VRPL" in sec else 0
        variants = []
        for idx in range(n_variants):
            lower_id, surf_off, surf_len, freq, vflags = struct.unpack_from("<IIHBB", b, vo + idx * 12)
            if surf_off == 0xFFFFFFFF:
                surf = lowers[lower_id]
            else:
                surf = b[vrp + surf_off:vrp + surf_off + surf_len].decode("utf-8")
            variants.append((surf, freq, lower_id, bool(vflags & 1)))

        kpo, _ = sec[b"KPOF"]
        kpp, _ = sec[b"KPPL"]
        kpvs, _ = sec[b"KPVS"]
        kpvi, _ = sec[b"KPVI"]
        kp_offs = struct.unpack_from(f"<{n_kps + 1}I", b, kpo)
        kp_vstarts = struct.unpack_from(f"<{n_kps + 1}I", b, kpvs)
        n_links = kp_vstarts[n_kps]
        kp_vids = struct.unpack_from(f"<{n_links}I", b, kpvi)
        kp_to_vids = {}
        for k in range(n_kps):
            kp = b[kpp + kp_offs[k]:kpp + kp_offs[k + 1] - 1].decode("utf-8")
            kp_to_vids[kp] = list(kp_vids[kp_vstarts[k]:kp_vstarts[k + 1]])

        bgst_o, _ = sec[b"BGST"]
        bg_starts = struct.unpack_from(f"<{n_lower + 1}I", b, bgst_o)
        bigrams = {}
        if n_big > 0:
            bgwd_o, _ = sec[b"BGWD"]
            bglc_o, _ = sec[b"BGLC"]
            bg_words = struct.unpack_from(f"<{n_big}I", b, bgwd_o)
            for p in range(n_lower):
                s, e = bg_starts[p], bg_starts[p + 1]
                if s < e:
                    bigrams[p] = {bg_words[j]: b[bglc_o + j] for j in range(s, e)}

        trigrams = {}
        if n_tri_ctx > 0:
            tgct_o, _ = sec[b"TGCT"]
            tgcl_o, _ = sec[b"TGCL"]
            tgst_o, _ = sec[b"TGST"]
            tgwd_o, _ = sec[b"TGWD"]
            tglc_o, _ = sec[b"TGLC"]
            tg_ctxs = struct.unpack_from(f"<{n_tri_ctx}Q", b, tgct_o)
            tg_starts = struct.unpack_from(f"<{n_tri_ctx + 1}I", b, tgst_o)
            tg_words = struct.unpack_from(f"<{n_tri}I", b, tgwd_o) if n_tri > 0 else ()
            for c in range(n_tri_ctx):
                key = tg_ctxs[c]
                w1, w2 = key >> 32, key & 0xFFFFFFFF
                s, e = tg_starts[c], tg_starts[c + 1]
                if s < e:
                    trigrams[(w1, w2)] = (b[tgcl_o + c], {tg_words[j]: b[tglc_o + j] for j in range(s, e)})

        return {
            "lowers": lowers,
            "lower_logc_q": lower_logc_q,
            "variants": variants,
            "kp_to_vids": kp_to_vids,
            "bigrams": bigrams,
            "trigrams": trigrams,
            "total": total,
        }

    raise SystemExit(f"{path}: unrecognized .kpd magic {magic!r}")


def write_kpd_v2(out_path, lowers, lower_logc_q, variants, kp_to_vids, bigrams, trigrams, total):
    """Write a flat zero-copy mmap-able .kpd v2 ('DKPD') dictionary."""
    n_lower = len(lowers)
    n_variants = len(variants)
    assert len(lower_logc_q) == n_lower

    # Sort key paths by UTF-8/ASCII bytes
    sorted_kps = sorted(kp for kp, vids in kp_to_vids.items() if kp and vids)
    n_kps = len(sorted_kps)

    # 1. Build temporary trie over sorted_kps and flatten in BFS order (implicit contiguous children!)
    # Each raw node: [children_dict(byte->raw_id), term_kpid, max_freq, depth]
    raw_children = [{}]
    raw_term = [-1]
    raw_freq = [0]
    raw_depth = [0]
    trie_has_non_az = False

    for kpid, kp in enumerate(sorted_kps):
        kpb = kp.encode("utf-8")
        max_f = max(variants[v][1] for v in kp_to_vids[kp])
        cur = 0
        for depth, byte_val in enumerate(kpb, start=1):
            if not (97 <= byte_val <= 122):
                trie_has_non_az = True
            ch = raw_children[cur]
            nxt = ch.get(byte_val)
            if nxt is None:
                nxt = len(raw_children)
                ch[byte_val] = nxt
                raw_children.append({})
                raw_term.append(-1)
                raw_freq.append(0)
                raw_depth.append(depth)
            cur = nxt
        raw_term[cur] = kpid
        if max_f > raw_freq[cur]:
            raw_freq[cur] = max_f

    # BFS renumbering: node 0 is root; children of BFS node u are [child_start[u], child_start[u+1])
    n_nodes = len(raw_children)
    child_start = [0] * (n_nodes + 1)
    node_char = bytearray(n_nodes)
    node_term = [0] * n_nodes
    node_freq = bytearray(n_nodes)
    node_depth = [0] * n_nodes

    q = deque([(0, 0)])  # (raw_id, edge_byte)
    next_id = 1
    bfs_idx = 0
    while q:
        raw_id, ch_byte = q.popleft()
        u = bfs_idx
        bfs_idx += 1
        node_char[u] = ch_byte
        node_term[u] = raw_term[raw_id]
        node_freq[u] = raw_freq[raw_id]
        node_depth[u] = min(65535, raw_depth[raw_id])
        child_start[u] = next_id
        ch_items = sorted(raw_children[raw_id].items())
        next_id += len(ch_items)
        for cb, child_raw in ch_items:
            q.append((child_raw, cb))
    assert bfs_idx == n_nodes and next_id == n_nodes
    child_start[n_nodes] = n_nodes

    # 2. Lowercase string pool + offsets + best_variant + byte_range
    lower_pool = bytearray()
    lower_off = [0] * (n_lower + 1)
    first_bytes = bytearray(n_lower)
    for i, low in enumerate(lowers):
        lower_off[i] = len(lower_pool)
        lb = low.encode("utf-8")
        first_bytes[i] = lb[0] if lb else 0
        lower_pool += lb + b"\x00"
    lower_off[n_lower] = len(lower_pool)

    byte_range = [n_lower] * 257
    cur = 0
    for bval in range(256):
        while cur < n_lower and first_bytes[cur] < bval:
            cur += 1
        byte_range[bval] = cur
    byte_range[256] = n_lower

    best_variant = [0xFFFFFFFF] * n_lower
    for vid, (_, freq, lid, _) in enumerate(variants):
        bcur = best_variant[lid]
        if bcur == 0xFFFFFFFF or freq > variants[bcur][1]:
            best_variant[lid] = vid

    # 3. Key-path string pool + offsets + kp->variants CSR
    kp_pool = bytearray()
    kp_off = [0] * (n_kps + 1)
    kp_var_start = [0] * (n_kps + 1)
    kp_var_ids = []
    for kpid, kp in enumerate(sorted_kps):
        kp_off[kpid] = len(kp_pool)
        kp_pool += kp.encode("utf-8") + b"\x00"
        kp_var_start[kpid] = len(kp_var_ids)
        kp_var_ids.extend(kp_to_vids[kp])
    kp_off[n_kps] = len(kp_pool)
    kp_var_start[n_kps] = len(kp_var_ids)

    # 4. FlatVariant[n_variants] + non-lowercase surface pool VRPL
    var_surf_pool = bytearray()
    vars_buf = bytearray(n_variants * 12)
    for vid, (surf, freq, lid, cap) in enumerate(variants):
        if surf == lowers[lid]:
            surf_off = 0xFFFFFFFF
            surf_len = 0
        else:
            sb = surf.encode("utf-8")
            surf_off = len(var_surf_pool)
            surf_len = min(len(sb), 65535)
            var_surf_pool += sb + b"\x00"
        struct.pack_into("<IIHBB", vars_buf, vid * 12, lid, surf_off, surf_len, freq, 1 if cap else 0)

    # 5. Bigrams CSR + precomputed unallocated-mass backoff big_rem[n_lower]
    exp_u8 = [math.exp(i / 16.0) for i in range(256)]
    big_start = [0] * (n_lower + 1)
    big_word = []
    big_logc = bytearray()
    big_rem = [0.0] * n_lower
    for p in range(n_lower):
        big_start[p] = len(big_word)
        c_prev = math.exp(lower_logc_q[p] / 2048.0)
        row = bigrams.get(p)
        bsum = 0.0
        if row:
            for w in sorted(row):
                q8 = row[w]
                big_word.append(w)
                big_logc.append(q8)
                bsum += exp_u8[q8]
        rem = max(0.15 * c_prev, c_prev - bsum)
        big_rem[p] = float(max(50.0, rem))
    big_start[n_lower] = len(big_word)
    n_big = len(big_word)

    # 6. Trigrams CSR + precomputed tri_rem[n_tri_ctx]
    sorted_tri_keys = sorted(trigrams.keys())
    n_tri_ctx = len(sorted_tri_keys)
    tri_ctx = []
    tri_ctx_logc = bytearray()
    tri_rem = []
    tri_start = [0] * (n_tri_ctx + 1)
    tri_word = []
    tri_logc = bytearray()
    for cidx, (w1, w2) in enumerate(sorted_tri_keys):
        ctx_q, row = trigrams[(w1, w2)]
        tri_ctx.append((w1 << 32) | w2)
        tri_ctx_logc.append(ctx_q)
        tri_start[cidx] = len(tri_word)
        tsum = 0.0
        for w3 in sorted(row):
            q8 = row[w3]
            tri_word.append(w3)
            tri_logc.append(q8)
            tsum += exp_u8[q8]
        cctx = exp_u8[ctx_q]
        tri_rem.append(float(max(0.15 * cctx, cctx - tsum)))
    tri_start[n_tri_ctx] = len(tri_word)
    n_tri = len(tri_word)

    # 7. Fast predict tables: TOPW (top-128 overall) + TPBS/TPBI (top-64 per leading byte)
    top_words = sorted(range(n_lower), key=lambda i: (-lower_logc_q[i], i))[:128]
    top_by_byte_start = [0] * 257
    top_by_byte_ids = []
    for bval in range(256):
        top_by_byte_start[bval] = len(top_by_byte_ids)
        lo, hi = byte_range[bval], byte_range[bval + 1]
        if lo < hi:
            top_b = sorted(range(lo, hi), key=lambda i: (-lower_logc_q[i], i))[:64]
            top_by_byte_ids.extend(top_b)
    top_by_byte_start[256] = len(top_by_byte_ids)

    # Pack all sections (every section starts on an 8-byte boundary)
    sections = [
        (b"TCHS", struct.pack(f"<{n_nodes + 1}I", *child_start)),
        (b"TNCH", bytes(node_char)),
        (b"TNTM", struct.pack(f"<{n_nodes}i", *node_term)),
        (b"TNFQ", bytes(node_freq)),
        (b"TNDP", struct.pack(f"<{n_nodes}H", *node_depth)),
        (b"LWPL", bytes(lower_pool)),
        (b"LWOF", struct.pack(f"<{n_lower + 1}I", *lower_off)),
        (b"LOGC", struct.pack(f"<{n_lower}H", *lower_logc_q)),
        (b"BEST", struct.pack(f"<{n_lower}I", *best_variant)),
        (b"KPPL", bytes(kp_pool)),
        (b"KPOF", struct.pack(f"<{n_kps + 1}I", *kp_off)),
        (b"KPVS", struct.pack(f"<{n_kps + 1}I", *kp_var_start)),
        (b"KPVI", struct.pack(f"<{len(kp_var_ids)}I", *kp_var_ids)),
        (b"VARS", bytes(vars_buf)),
        (b"VRPL", bytes(var_surf_pool)),
        (b"BGST", struct.pack(f"<{n_lower + 1}I", *big_start)),
        (b"BGRM", struct.pack(f"<{n_lower}f", *big_rem)),
    ]
    if n_big > 0:
        sections.append((b"BGWD", struct.pack(f"<{n_big}I", *big_word)))
        sections.append((b"BGLC", bytes(big_logc)))
    if n_tri_ctx > 0:
        sections.append((b"TGCT", struct.pack(f"<{n_tri_ctx}Q", *tri_ctx)))
        sections.append((b"TGCL", bytes(tri_ctx_logc)))
        sections.append((b"TGRM", struct.pack(f"<{n_tri_ctx}f", *tri_rem)))
        sections.append((b"TGST", struct.pack(f"<{n_tri_ctx + 1}I", *tri_start)))
        if n_tri > 0:
            sections.append((b"TGWD", struct.pack(f"<{n_tri}I", *tri_word)))
            sections.append((b"TGLC", bytes(tri_logc)))
    sections.extend([
        (b"TOPW", struct.pack(f"<{len(top_words)}I", *top_words)),
        (b"TPBS", struct.pack("<257I", *top_by_byte_start)),
        (b"TPBI", struct.pack(f"<{len(top_by_byte_ids)}I", *top_by_byte_ids)),
        (b"BYRG", struct.pack("<257I", *byte_range)),
    ])

    flags = FLAG_BFS_TRIE | (FLAG_TRIE_HAS_NON_AZ if trie_has_non_az else 0) | FLAG_HAS_FOOTER
    header = b"DKPD" + struct.pack(
        "<9Id2I",
        2, flags, n_lower, n_variants, n_kps, n_nodes, n_big, n_tri_ctx, n_tri,
        float(total), len(sections), 0,
    )
    assert len(header) == 56
    offset = len(header) + 12 * len(sections)
    offset = (offset + 7) & ~7
    directory = bytearray()
    offsets = []
    for tag, data in sections:
        offsets.append(offset)
        directory += tag + struct.pack("<II", offset, len(data))
        offset = (offset + len(data) + 7) & ~7

    payload = bytearray()
    payload += header
    payload += directory
    for (_, data), sec_off in zip(sections, offsets):
        if sec_off > len(payload):
            payload += b"\x00" * (sec_off - len(payload))
        payload += data
    end_pad = (-len(payload)) % 8
    if end_pad:
        payload += b"\x00" * end_pad
    total_size = len(payload) + FOOTER_SIZE
    footer = b"DKPF" + struct.pack("<IQQ", 0, total_size, fast_hash64(payload))
    assert len(footer) == FOOTER_SIZE

    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    with open(out_path, "wb") as f:
        f.write(payload)
        f.write(footer)
        f.flush()
        os.fsync(f.fileno())

    print(f"{out_path} [v2]: {n_lower} words, {n_variants} variants, {n_kps} kps, {n_nodes} nodes, "
          f"{n_big} bigrams, {n_tri} trigrams, {total_size / 1e6:.2f} MB")


def repack_v2(in_kpd, out_kpd, prune_words=None, add_aliases=None):
    """Convert an existing .kpd (v1 or v2) to flat mmap-able .kpd v2, optionally pruning words
    and adding collapsed-double key-path aliases (e.g. {'il': \"I'll\", 'wel': \"we'll\"})."""
    d = read_kpd_data(in_kpd)
    lowers = d["lowers"]
    lower_logc_q = d["lower_logc_q"]
    variants = d["variants"]
    kp_to_vids = d["kp_to_vids"]
    bigrams = d["bigrams"]
    trigrams = d["trigrams"]
    total = d["total"]

    prune_set = {native_lower(w) for w in (prune_words or ())}
    if prune_set:
        old_to_new_low = {}
        new_lowers = []
        new_logc_q = []
        for old_id, low in enumerate(lowers):
            if low in prune_set:
                continue
            old_to_new_low[old_id] = len(new_lowers)
            new_lowers.append(low)
            new_logc_q.append(lower_logc_q[old_id])

        old_to_new_vid = {}
        new_variants = []
        for old_vid, (surf, freq, old_lid, cap) in enumerate(variants):
            new_lid = old_to_new_low.get(old_lid)
            if new_lid is None:
                continue
            old_to_new_vid[old_vid] = len(new_variants)
            new_variants.append((surf, freq, new_lid, cap))

        new_kp_to_vids = {}
        for kp, vids in kp_to_vids.items():
            kept = [old_to_new_vid[v] for v in vids if v in old_to_new_vid]
            if kept:
                new_kp_to_vids[kp] = kept

        new_bigrams = {}
        for p, row in bigrams.items():
            np_id = old_to_new_low.get(p)
            if np_id is None:
                continue
            nrow = {old_to_new_low[w]: q for w, q in row.items() if w in old_to_new_low}
            if nrow:
                new_bigrams[np_id] = nrow

        new_trigrams = {}
        for (w1, w2), (ctx_q, row) in trigrams.items():
            nw1, nw2 = old_to_new_low.get(w1), old_to_new_low.get(w2)
            if nw1 is None or nw2 is None:
                continue
            nrow = {old_to_new_low[w3]: q for w3, q in row.items() if w3 in old_to_new_low}
            if nrow:
                new_trigrams[(nw1, nw2)] = (ctx_q, nrow)

        lowers, lower_logc_q, variants, kp_to_vids, bigrams, trigrams = (
            new_lowers, new_logc_q, new_variants, new_kp_to_vids, new_bigrams, new_trigrams
        )

    if add_aliases:
        surf_to_vids = {}
        for vid, (surf, _, _, _) in enumerate(variants):
            surf_to_vids.setdefault(surf, []).append(vid)
        for alias_kp, targets in add_aliases.items():
            if isinstance(targets, str):
                targets = [targets]
            bucket = kp_to_vids.setdefault(alias_kp, [])
            for tgt in targets:
                vids = surf_to_vids.get(tgt)
                if not vids:
                    print(f"warning: alias target {tgt!r} not found in {in_kpd}", file=sys.stderr)
                    continue
                for vid in vids:
                    if vid not in bucket:
                        bucket.append(vid)

    write_kpd_v2(out_kpd, lowers, lower_logc_q, variants, kp_to_vids, bigrams, trigrams, total)


def repack_all_v2(in_dir, out_dir):
    os.makedirs(out_dir, exist_ok=True)
    paths = sorted(glob.glob(os.path.join(in_dir, "*.kpd")))
    if not paths:
        raise SystemExit(f"no .kpd files in {in_dir}")
    for p in paths:
        name = os.path.basename(p)
        out_p = os.path.join(out_dir, name)
        if name == "en.kpd":
            repack_v2(p, out_p, prune_words=DEFAULT_EN_PRUNE, add_aliases=DEFAULT_EN_ALIASES)
        else:
            repack_v2(p, out_p)


def main():
    argv = sys.argv[1:]
    if "--repack-all" in argv:
        pos = [a for a in argv if not a.startswith("--")]
        if len(pos) != 2:
            raise SystemExit("usage: pack_swipe_dict.py --repack-all <in_dir> <out_dir>")
        repack_all_v2(pos[0], pos[1])
        return

    if "--repack-v2" in argv:
        prune = []
        aliases = {}
        pos = []
        for a in argv:
            if a == "--repack-v2":
                continue
            elif a.startswith("--prune="):
                prune.extend(x for x in a.split("=", 1)[1].split(",") if x)
            elif a.startswith("--alias="):
                for item in a.split("=", 1)[1].split(","):
                    if ":" in item:
                        kp, surf = item.split(":", 1)
                        aliases.setdefault(kp, []).append(surf)
            elif not a.startswith("--"):
                pos.append(a)
        if len(pos) != 2:
            raise SystemExit("usage: pack_swipe_dict.py --repack-v2 <in.kpd> <out.kpd> [--prune=...] [--alias=...]")
        repack_v2(pos[0], pos[1], prune_words=prune, add_aliases=aliases)
        return

    args = [a for a in argv if not a.startswith("--")]
    min_bigram = 1
    trigrams_tsv = None
    min_trigram = 3
    trigram_bytes = 2_000_000
    total_override = None
    use_v2 = False
    for a in argv:
        if a == "--v2":
            use_v2 = True
        elif a.startswith("--min-bigram="):
            min_bigram = int(a.split("=", 1)[1])
        elif a.startswith("--trigrams="):
            trigrams_tsv = a.split("=", 1)[1]
        elif a.startswith("--min-trigram="):
            min_trigram = int(a.split("=", 1)[1])
        elif a.startswith("--trigram-bytes="):
            trigram_bytes = int(a.split("=", 1)[1])
        elif a.startswith("--total="):
            total_override = float(a.split("=", 1)[1])
    words_tsv, bigrams_tsv, out = args

    by_lower = {}     # lower -> list of (surface, freq) in TSV order
    lower_count = {}
    keypaths = {}
    n_variants = 0
    for line in open(words_tsv, encoding="utf-8"):
        f = line.rstrip("\n").split("\t")
        if len(f) != 4 or not f[1]:
            continue
        surface, kp, freq, count = f[0], f[1], int(float(f[2])), float(f[3])
        low = native_lower(surface)
        by_lower.setdefault(low, []).append((surface, max(0, min(255, freq))))
        lower_count.setdefault(low, count)  # first variant's count, like the TSV loader
        keypaths.setdefault(low, kp)
        n_variants += 1

    # letters other than a-z that occur in key-path derivations
    cmap = {}
    for low in by_lower:
        for ch in low:
            if not ("a" <= ch <= "z") and ch.isalpha():
                base = unicodedata.normalize("NFD", ch)[0]
                if "a" <= base <= "z":
                    cmap[ord(ch)] = base

    def derive(low):
        return "".join(ch if "a" <= ch <= "z" else cmap.get(ord(ch), "") for ch in low)

    lowers = sorted(by_lower, key=lambda s: s.encode())
    lower_id = {s: i for i, s in enumerate(lowers)}
    total = total_override or float(sum(lower_count.values())) or 1.0

    bigrams = {}
    n_big = 0
    for line in open(bigrams_tsv, encoding="utf-8"):
        f = line.rstrip("\n").split("\t")
        if len(f) != 3:
            continue
        a, b, c = native_lower(f[0]), native_lower(f[1]), float(f[2])
        if c < min_bigram or a not in lower_id or b not in lower_id:
            continue
        bigrams.setdefault(lower_id[a], {})[lower_id[b]] = c
        n_big += 1

    kept_tri = {}
    if trigrams_tsv:
        rows = []
        for line in open(trigrams_tsv, encoding="utf-8"):
            f = line.rstrip("\n").split("\t")
            if len(f) != 5 or float(f[3]) < min_trigram:
                continue
            ids = [lower_id.get(native_lower(w)) for w in f[:3]]
            if None in ids:
                continue
            rows.append((float(f[3]), ids[0], ids[1], ids[2], float(f[4])))
        rows.sort(key=lambda r: -r[0])
        size = 8
        for c, w1, w2, w3, cc in rows:
            ctx = kept_tri.get((w1, w2))
            cost = 3 + (0 if ctx else 6)
            if size + cost > trigram_bytes:
                break
            if ctx is None:
                ctx = kept_tri[(w1, w2)] = [cc, {}]
            ctx[1][w3] = c
            size += cost

    if use_v2:
        lower_logc_q = [max(0, min(65535, round(math.log(max(1, lower_count[s_])) * 2048))) for s_ in lowers]
        variants = []
        kp_to_vids = {}
        for wid, low in enumerate(lowers):
            kp = keypaths[low]
            for surf, fr in by_lower[low]:
                vid = len(variants)
                variants.append((surf, fr, wid, is_capitalized_surface(surf)))
                if kp:
                    kp_to_vids.setdefault(kp, []).append(vid)
        big_q = {
            p: {w: max(0, min(255, round(math.log(c) * 16))) for w, c in row.items()}
            for p, row in bigrams.items()
        }
        tri_q = {
            k: (
                max(0, min(255, round(math.log(max(1, cc)) * 16))),
                {w: max(0, min(255, round(math.log(c) * 16))) for w, c in conts.items()},
            )
            for k, (cc, conts) in kept_tri.items()
        }
        write_kpd_v2(out, lowers, lower_logc_q, variants, kp_to_vids, big_q, tri_q, total)
        return

    # v1 ("KPD1") writer
    sections = []  # (tag, bytes)
    cm = bytearray(struct.pack("<I", len(cmap)))
    for cp in sorted(cmap):
        cm += struct.pack("<IB", cp, ord(cmap[cp]))
    sections.append((b"CMAP", cm))
    lw = bytearray()
    prev = ""
    for s_ in lowers:
        lw += front(prev, s_)
        prev = s_
    sections.append((b"LOWR", lw))
    lc = bytearray()
    for s_ in lowers:
        lc += struct.pack("<H", max(0, min(65535, round(math.log(max(1, lower_count[s_])) * 2048))))
    sections.append((b"LOGC", lc))
    rec = bytearray()
    freqs = bytearray()
    explicit_kp = 0
    explicit_surf = 0
    for low in lowers:
        vs = by_lower[low]
        has_low = [fr for s_, fr in vs if s_ == low]
        has_cap = [fr for s_, fr in vs if s_ != low and s_ == capitalized(low)]
        rest = [(s_, fr) for s_, fr in vs if s_ != low and s_ != capitalized(low)]
        kp = keypaths[low]
        own_kp = derive(low) != kp
        spec = (1 if has_low else 0) | (2 if has_cap else 0) | (4 if own_kp else 0) | (len(rest) << 3)
        rec += varint(spec)
        if own_kp:
            kb = kp.encode()
            rec += varint(len(kb)) + kb
            explicit_kp += 1
        for s_, _ in rest:
            sb = s_.encode()
            rec += varint(len(sb)) + sb
            explicit_surf += 1
        freqs += bytes(has_low[:1] + has_cap[:1] + [fr for _, fr in rest])
    assert len(freqs) == n_variants, (len(freqs), n_variants)
    sections.append((b"WREC", rec))
    sections.append((b"FREQ", freqs))
    bg = bytearray()
    last_prev = 0
    for p in sorted(bigrams):
        bg += varint(p - last_prev) + varint(len(bigrams[p]))
        last_prev = p
        last_w = 0
        for w in sorted(bigrams[p]):
            bg += varint(w - last_w) + bytes([max(0, min(255, round(math.log(bigrams[p][w]) * 16)))])
            last_w = w
    sections.append((b"BIGR", bg))

    n_tri = 0
    if kept_tri:
        tg = bytearray()
        last1 = last2 = 0
        body = bytearray()
        for (w1, w2) in sorted(kept_tri):
            cc, conts = kept_tri[(w1, w2)]
            if w1 != last1 or not body:
                body += varint(w1 - last1) + varint(w2)
            else:
                body += varint(0) + varint(w2 - last2)
            last1, last2 = w1, w2
            body += bytes([max(0, min(255, round(math.log(max(1, cc)) * 16)))]) + varint(len(conts))
            last_w = 0
            for w in sorted(conts):
                body += varint(w - last_w) + bytes([max(0, min(255, round(math.log(conts[w]) * 16)))])
                last_w = w
            n_tri += len(conts)
        tg += struct.pack("<II", len(kept_tri), n_tri) + body
        sections.append((b"TRIG", tg))

    header = b"KPD1" + struct.pack("<IIIIIdI", 2, len(lowers), n_variants, len(bigrams), n_big, total, len(sections))
    offset = len(header) + 12 * len(sections)
    directory = bytearray()
    for tag, data in sections:
        directory += tag + struct.pack("<II", offset, len(data))
        offset += len(data)
    buf = bytearray(header) + directory
    for _, data in sections:
        buf += data
    with open(out, "wb") as o:
        o.write(buf)
    print(f"{out}: {len(lowers)} words, {n_variants} variants ({explicit_surf} explicit, "
          f"{explicit_kp} explicit key paths), {n_big} bigrams, {n_tri} trigrams, {len(buf)} bytes")


if __name__ == "__main__":
    main()
