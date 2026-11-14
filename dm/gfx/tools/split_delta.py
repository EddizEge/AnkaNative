#!/usr/bin/env python3
# The shader split of a delta update (shader_split.py with DM_SPLIT_DELTA=1, the phone's split step: PackService; android/SHADERS.md#the-split-of-an-update).
# A delta update keeps every entry of the pack's older library whose hash the profile's manifest (vk_delta.tsv) has unchanged and translates only the rest
# (vk_android_build.py's delta), so its split needs the package records of only those shaders. Ocarina of Time's update from the 0.2.2 library translates 584 of
# 42,854 shaders, which sit in 37 of the dump's 186,583 RomFS files: reading those instead of every file is what makes the update minutes instead of half an hour.
#   needed():  the shaders the build will translate, decided as vk_android_build.py decides it (its delta section and older_libraries): the profile's map keys less
#              those an older library of DMSH_VK_DELTA_FROM holds with the manifest's hash (each entry hashed, as the build does), and every "-" key (the build tries
#              those again). It errs only on the side of more: a library the build might take but this cannot read is not taken here.
#   files_for(): the RomFS files that hold them: the profile's shader_map.tsv column 5 names where each key first appears in a full split, a RomFS file or a member
#              inside one; its file is the prefix of the source, ending at a '/' or '#' or the end, that is a file of the dump (after Banana Native's targeted
#              split, f86575b0 profile_files, git.eden-emu.dev/moceure/BananaNative, MIT). Main and the other sources that are no RomFS file are read on every run.
#   unproven(): after the scan, why its package may not be the full split's for those shaders: a needed key not found, or found first at another place than the
#              profile's map says. A key's record is its control section and code area as found first, so the same first place is the same record, byte for byte.
# shader_split.py scans every file whenever any of this cannot be shown (no older library, a manifest of other tools or inputs, a source the dump lacks, a key not
# found where the map says); the phone's shaders step splits everything again when the build still finds a shader missing (PackService).
import concurrent.futures as cf, os, re

HERE = os.path.dirname(os.path.abspath(__file__)); GFX = os.path.dirname(HERE)
HEADER_VK = os.path.join(GFX, 'nvn', 'dmsh_chain_vk.h')
NOT_ROMFS = ('/glslc_capture/', '/ue_shaders/', '/nvn_shdump')   # map sources that are no RomFS file (main's have no leading '/'): shader_split.py reads them on every run


def zstd_mod():
    try: from compression import zstd
    except ImportError:
        try: from backports import zstd   # pyright: ignore[reportMissingImports]
        except ImportError: zstd = None
    return zstd


def tree_version():
    """DMSH_VK_CHAIN as this tree's dmsh_chain_vk.h has it: the version vk_android_build.py builds (it stops when the header is not its version)"""
    with open(HEADER_VK) as f: m = re.search(r'#define DMSH_VK_CHAIN "([0-9a-f]+)"', f.read())
    return m.group(1) if m else None


def profile_sources(game):
    """{key: source} of the profile's shader_map.tsv: column 5, where a full split found the key first"""
    out = {}
    with open(game.table('shader_map.tsv'), encoding='utf-8') as f:
        for ln in f:
            c = ln.rstrip('\n').split('\t')
            if len(c) > 4 and c[0].endswith('.bin'): out.setdefault(c[3], c[4])
    return out


def library_files(roots, lib_file='dmsh_vk.lib', prefix='v-'):
    """The library files of DMSH_VK_DELTA_FROM's paths (os.pathsep between them): each a library file, a v-<V> folder or a folder of them, as vk_android_build.py
    older_libraries takes them"""
    files = []
    for r in roots:
        if os.path.isfile(r): files.append(r); continue
        if os.path.isfile(os.path.join(r, lib_file)): files.append(os.path.join(r, lib_file)); continue
        try: subs = os.listdir(r)
        except OSError: continue
        files += [os.path.join(r, n, lib_file) for n in subs if n.startswith(prefix) and os.path.isfile(os.path.join(r, n, lib_file))]
    return sorted(set(os.path.realpath(f) for f in files))


def needed(game, roots, nj=4, log=print):
    """(the keys the delta build will translate, ''), or (None, why the build is no delta a split could serve). roots: DMSH_VK_DELTA_FROM's paths"""
    import dmsh_chain, vk_android_delta as delta
    zstd = zstd_mod()
    if zstd is None or os.environ.get('DMSH_VK_RAW') == '1': return None, 'the build would store its modules raw, without a manifest (DMSH_VK_RAW, or no zstd)'
    if not roots: return None, 'no older library is named (DMSH_VK_DELTA_FROM)'
    try:
        with open(game.table(delta.MANIFEST), encoding='utf-8') as f: m = delta.parse_manifest(f.read())
    except (OSError, ValueError) as e: return None, f'no manifest of the profile ({e})'
    v, inputs = tree_version(), dmsh_chain.inputs(game)
    if (m['version'], m['game'], m['inputs']) != (v, game.id, inputs):
        return None, f'the profile\'s manifest is of version {m["version"]}, game {m["game"]}, inputs {m["inputs"]}; the build is {v}, {game.id}, {inputs}'
    keys = list(profile_sources(game))
    libs = []
    for p in library_files(roots, delta.VK['LIB_FILE'], delta.VK['DIR_PREFIX']):
        try: L = delta.Library(p)
        except (OSError, ValueError) as e: log(f'delta split: {p} skipped: {e}', flush=True); continue
        # the build skips the output's own library when it is of these inputs; one of this version and these inputs here is not taken either: more to translate,
        # never less
        if L.game != game.id or (L.version, L.inputs) == (v, inputs): L.close(); continue
        libs.append(L)
    if not libs: return None, 'no older library of this game'
    try:
        hashes = m['hashes']
        cand = [k for k in keys if hashes.get(k, '-') != '-' and any(k in L.index for L in libs)]
        def kept(k):   # as the build's _reuse: an entry of any older library with the manifest's hash; one that cannot be read is not
            for L in libs:
                i = L.index.get(k)
                if i is None: continue
                try: _, h, _ = L.hash_of(i, zstd)
                except Exception: continue
                if h == hashes[k]: return True
            return False
        with cf.ThreadPoolExecutor(max(1, nj)) as ex: keep = {k for k, ok in zip(cand, ex.map(kept, cand)) if ok}
    finally:
        for L in libs: L.close()
    return {k for k in keys if k not in keep}, ''


def files_for(keys, sources, is_file):
    """({RomFS file (the scan's relative path)}, '') holding the keys' first appearances, or (None, why not). sources: profile_sources; is_file(relative path): whether
    the dump has that file (a folder is not one)"""
    out = set()
    for k in sorted(keys):
        s = sources.get(k)
        if s is None: return None, f'{k} is not in the profile\'s shader_map.tsv'
        if not s.startswith('/') or s.startswith(NOT_ROMFS): continue   # main's (and the captures and dumps beside it): read on every run
        s = s[1:]
        r = next((s[:i] for i in [i for i, ch in enumerate(s) if ch in '/#'] + [len(s)] if is_file(s[:i])), None)
        if r is None: return None, f'the profile\'s shader_map.tsv has {k} in /{s}, and the dump has no such file'
        out.add(r)
    return out, ''


def unproven(keys, sources, found):
    """Why the scan's package may not hold the full split's record of each key ([] when it does). found: {key: where the scan found it first}"""
    bad = []
    for k in sorted(keys):
        w = found.get(k)
        if w is None: bad.append(f'{k} was not found')
        elif w != sources.get(k): bad.append(f'{k} was found first at {w}, the profile\'s map has {sources.get(k)}')
    return bad
