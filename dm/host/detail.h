// Game settings › Ground plants and stones (DM_DETAIL; profile rows detail_level / detail_param / detail_env / detail_zsdic, dm/games/README.md): a RomFS overlay made at
// start from the player's own files, with values of the game's own parameter files edited in place, handed to the game through DM_ROMFS_OVERLAY. Header only,
// so that dm/host/tests/t_detail.c runs the parser, the BYML and SARC editors, the zstd frame writer and the whole build on any machine; hle_fs.c dm_detail_start
// gives it the RomFS reader (a folder, a RomFS image or a NUP package) and zstd's decoder.
//
// What a level does. Each detail_param row names files (a RomFS path whose last component may be a glob, or "pack:<pack path>:<member glob>" for members of a
// zstd-compressed SARC such as Pack/Foliage.pack.zs), a key path in their BYML (components separated by '.', each a glob over a dictionary's keys or an array
// index) and an edit: "x<factor>" scales a number (an integer is rounded), "=true" / "=false" sets a boolean, "=<number>" sets a number. Edits are in place: a
// file's size never changes (the game's resource size table holds the sizes). A key a matched file does not have is left to the game's defaults (scenes carry only
// the keys they change); a row whose key is in none of its files, a path that matches nothing, a value of another type, a malformed file or a pack that cannot be
// decoded is a "[RED] " line naming it, and that file (or member) stays the game's own. The overlay is built in a folder of its own beside the game's temporary
// storage, "<DM_TEMP>_detail" (as <DM_TEMP>_sysdata: DM_TEMP itself is the game's temp: mount, whose folders the game lists and deletes), into
// "detail-<levels>-<digest>.tmp", then renamed: the digest covers the rows, the switches below and every source file's bytes, so a changed row or a game update
// builds a new one, and a folder without its manifest (a build cut off) is built again. The game's files are under its "romfs/" (the folder DM_ROMFS_OVERLAY
// names), the manifest "detail.txt" beside it (never in the game's rom:/ listings): the summary line, then the build's red lines, which a later start that uses the
// folder says again (every failure is said at every start). One build at a time ("<DM_TEMP>_detail/.lock"); the other detail-* folders are removed. When nothing
// can be built the game starts as the game draws it (Full), with a red line. Full itself never reaches this code (the launcher sends nothing, and removes the
// folder).
//
// A pack member written back: the pack's SARC with the edited members in place, as one zstd frame of raw blocks (no compression: the host has only zstd's
// decoder). Its header is the source frame's: the same content size and, by default, the same dictionary ID (DM_DETAIL_PACK_DICTID=keep), so a loader that picks
// its dictionary by the frame's ID (Ocarina of Time's main+0xe8fa4: ID 0 decodes without one, any other ID is looked up in the registered dictionaries) finds the
// one it registered; raw blocks never read it. DM_DETAIL_PACK_DICTID=none writes the frame without an ID (a diagnostic). Before a pack is written it is decoded
// back with the decoder the host was given and must equal the edited SARC.
#pragma once
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <fnmatch.h>
#include <math.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <unistd.h>

#define DETAIL_MAX_LEVELS 16
#define DETAIL_MAX_PARAMS 256
#define DETAIL_MAX_ENV 32
#define DETAIL_VERSION "detail 2"   // in the digest: a change of what the builder writes builds every overlay again

enum { DETAIL_SCALE = 1, DETAIL_SET_BOOL = 2, DETAIL_SET_NUM = 3 };
enum { DETAIL_COPY_BOTH = 0, DETAIL_COPY_LOOSE = 1, DETAIL_COPY_PACK = 2 };
enum { DETAIL_DICTID_KEEP = 0, DETAIL_DICTID_NONE = 1 };

typedef struct { char id[32]; int shown; char label[160]; } detail_level;
typedef struct {
    uint32_t levels;                   // bit i: level i
    int pack;                          // 0: a loose file (path), 1: members (member) of the pack at path
    char path[256], member[256], key[256];
    int kind; double num; int b;       // the edit
    char text[900];                    // "<path> <key> <edit>", for the log
    unsigned files, found, hit_files, values;   // the build's counts: files (or members) it matched, those that had the key, those written with an edit, values edited
} detail_param;
typedef struct { uint32_t levels; char kv[256]; } detail_env;
typedef struct {
    detail_level lv[DETAIL_MAX_LEVELS]; int nlv;
    detail_param par[DETAIL_MAX_PARAMS]; int npar;
    detail_env env[DETAIL_MAX_ENV]; int nenv;
    char zsdic[256];                   // the RomFS path of the pack of zstd dictionaries (detail_zsdic), "" when the profile has none
} detail_profile;

// The log: every line the builder writes goes through one function (the host's LOG; the tests collect them)
typedef void (*detail_log_fn)(void *ctx, const char *line);
static void detail_say(detail_log_fn log, void *ctx, const char *fmt, ...) __attribute__((format(printf, 3, 4)));
static void detail_say(detail_log_fn log, void *ctx, const char *fmt, ...) {
    char b[8192]; va_list a; va_start(a, fmt); vsnprintf(b, sizeof b, fmt, a); va_end(a); log(ctx, b);
}

// ---- Rows ----

static int detail_id_ok(const char *s) {
    size_t n = strlen(s); if (!n || n > 31) return 0;
    for (size_t i = 0; i < n; i++) if (!((s[i] >= 'a' && s[i] <= 'z') || (s[i] >= '0' && s[i] <= '9') || s[i] == '_')) return 0;
    return 1;
}
static int detail_level_index(const detail_profile *p, const char *id) { for (int i = 0; i < p->nlv; i++) if (!strcmp(p->lv[i].id, id)) return i; return -1; }
// A RomFS path: relative, no empty, "." or ".." component, no backslash
static int detail_path_ok(const char *s) {
    if (!*s || *s == '/' || strchr(s, '\\') || strlen(s) >= 256) return 0;
    for (const char *c = s; *c;) {
        const char *e = strchr(c, '/'); size_t k = e ? (size_t)(e - c) : strlen(c);
        if (!k || (k == 1 && c[0] == '.') || (k == 2 && c[0] == '.' && c[1] == '.')) return 0;
        c += k; if (*c) c++; else break;
        if (!*c) return 0;   // a trailing slash
    }
    return 1;
}
static int detail_has_glob(const char *s, size_t n) { for (size_t i = 0; i < n; i++) if (s[i] == '*' || s[i] == '?' || s[i] == '[') return 1; return 0; }
// "a,b": the mask of those levels; 0 with why on an unknown id, the first level (it changes nothing) or an empty list
static uint32_t detail_levels_of(const detail_profile *p, const char *s, char *why, size_t wn) {
    uint32_t m = 0; char b[256]; snprintf(b, sizeof b, "%s", s);
    char *save = NULL;
    for (char *t = strtok_r(b, ",", &save); t; t = strtok_r(NULL, ",", &save)) {
        int i = detail_level_index(p, t);
        if (i < 0) { snprintf(why, wn, "unknown level %s", t); return 0; }
        if (!i) { snprintf(why, wn, "level %s is the first (as the game draws it): it takes no rows", t); return 0; }
        m |= 1u << i;
    }
    if (!m) snprintf(why, wn, "no level");
    return m;
}
static int detail_split(char *s, char **f, int max) {   // tab-separated fields, trimmed
    int n = 0; char *save = NULL;
    for (char *t = strtok_r(s, "\t", &save); t && n < max; t = strtok_r(NULL, "\t", &save)) {
        while (*t == ' ') t++;
        char *e = t + strlen(t); while (e > t && e[-1] == ' ') *--e = 0;
        f[n++] = t;
    }
    return n;
}
// An edit: x<factor> (a finite factor above 0), =true, =false, =<finite number>
static int detail_edit_parse(const char *s, int *kind, double *num, int *b) {
    char *end;
    if (s[0] == 'x') { double f = strtod(s + 1, &end); if (end == s + 1 || *end || !isfinite(f) || f <= 0) return 0; *kind = DETAIL_SCALE; *num = f; return 1; }
    if (s[0] != '=') return 0;
    if (!strcmp(s, "=true") || !strcmp(s, "=false")) { *kind = DETAIL_SET_BOOL; *b = s[1] == 't'; return 1; }
    double v = strtod(s + 1, &end); if (end == s + 1 || *end || !isfinite(v)) return 0;
    *kind = DETAIL_SET_NUM; *num = v; return 1;
}
// Switches the launcher sets (GameActivity.PROTECTED) and the setting's own may not be a level's detail_env
static int detail_env_key_ok(const char *kv) {
    const char *eq = strchr(kv, '='); if (!eq || eq == kv || !eq[1] || strchr(kv, ' ') || strchr(kv, '"') || strchr(kv, '\\')) return 0;
    size_t k = (size_t)(eq - kv); if (k < 4 || strncmp(kv, "DM_", 3)) return 0;
    for (size_t i = 3; i < k; i++) if (!((kv[i] >= 'A' && kv[i] <= 'Z') || (kv[i] >= '0' && kv[i] <= '9') || kv[i] == '_')) return 0;
    static const char *no[] = {"DM_SAVE", "DM_ROMFS", "DM_TEMP", "DM_ROOT", "DM_AOC_DIR", "DM_ROMFS_OVERLAY"};
    for (size_t i = 0; i < sizeof no / sizeof *no; i++) if (strlen(no[i]) == k && !strncmp(kv, no[i], k)) return 0;
    if (k >= 9 && !strncmp(kv, "DM_DETAIL", 9)) return 0;
    return 1;
}

// The profile's rows ("<key>\t<values...>", as game.tsv has them): the detail_level rows first, then the others. A malformed row, or one naming an unknown level,
// is a red line and is left out. Returns the number of red lines
static int detail_parse(detail_profile *p, const char *const *rows, int n, detail_log_fn log, void *ctx) {
    memset(p, 0, sizeof *p); int red = 0;
    for (int pass = 0; pass < 2; pass++)
        for (int r = 0; r < n; r++) {
            char b[1200]; snprintf(b, sizeof b, "%s", rows[r]);
            char *f[8]; int nf = detail_split(b, f, 8), lvl = nf && !strcmp(f[0], "detail_level");
            if (!nf || lvl != !pass) continue;
            char why[300] = "";
            if (lvl) {
                if (nf != 4 || !detail_id_ok(f[1]) || (strcmp(f[2], "shown") && strcmp(f[2], "hidden")) || strlen(f[3]) >= sizeof p->lv[0].label || strpbrk(f[3], "=;|\"\\"))
                    snprintf(why, sizeof why, "takes <id: a-z 0-9 _> <shown | hidden> <label (no = ; | quote or backslash)>");
                else if (detail_level_index(p, f[1]) >= 0) snprintf(why, sizeof why, "level %s is named twice", f[1]);
                else if (p->nlv == DETAIL_MAX_LEVELS) snprintf(why, sizeof why, "more than %d levels", DETAIL_MAX_LEVELS);
                else if (!p->nlv && strcmp(f[2], "shown")) snprintf(why, sizeof why, "the first level (as the game draws it, the default) must be shown");
                else { detail_level *l = &p->lv[p->nlv++]; snprintf(l->id, sizeof l->id, "%s", f[1]); l->shown = !strcmp(f[2], "shown"); snprintf(l->label, sizeof l->label, "%s", f[3]); }
            } else if (!strcmp(f[0], "detail_param")) {
                detail_param q; memset(&q, 0, sizeof q);
                if (nf != 5) snprintf(why, sizeof why, "takes <levels, ',' between them> <RomFS path, its last component a glob, or pack:<pack path>:<member glob>> <key path> <x<factor> | =true | =false | =<number>>");
                else if (!(q.levels = detail_levels_of(p, f[1], why, sizeof why))) {}
                else if (!detail_edit_parse(f[4], &q.kind, &q.num, &q.b)) snprintf(why, sizeof why, "edit %s: x<factor above 0>, =true, =false or =<number>", f[4]);
                else if (!*f[3] || strstr(f[3], "..") || f[3][0] == '.' || f[3][strlen(f[3]) - 1] == '.' || strlen(f[3]) >= sizeof q.key) snprintf(why, sizeof why, "key path %s: names separated by '.'", f[3]);
                else if (p->npar == DETAIL_MAX_PARAMS) snprintf(why, sizeof why, "more than %d detail_param rows", DETAIL_MAX_PARAMS);
                else {
                    if (!strncmp(f[2], "pack:", 5)) {
                        const char *c = strchr(f[2] + 5, ':');
                        if (!c || (size_t)(c - f[2] - 5) >= sizeof q.path) snprintf(why, sizeof why, "pack:<pack path>:<member glob>");
                        else {
                            memcpy(q.path, f[2] + 5, (size_t)(c - f[2] - 5)); snprintf(q.member, sizeof q.member, "%s", c + 1); q.pack = 1;
                            if (!detail_path_ok(q.path) || detail_has_glob(q.path, strlen(q.path)) || !detail_path_ok(q.member)) snprintf(why, sizeof why, "pack:<pack path, no glob>:<member glob>");
                        }
                    } else {
                        snprintf(q.path, sizeof q.path, "%s", f[2]);
                        const char *sl = strrchr(q.path, '/');
                        if (!detail_path_ok(q.path) || (sl && detail_has_glob(q.path, (size_t)(sl - q.path)))) snprintf(why, sizeof why, "a RomFS path whose folders have no glob");
                    }
                    if (!*why) { snprintf(q.key, sizeof q.key, "%s", f[3]); snprintf(q.text, sizeof q.text, "%s %s %s", f[2], f[3], f[4]); p->par[p->npar++] = q; }
                }
            } else if (!strcmp(f[0], "detail_env")) {
                uint32_t m = 0;
                if (nf != 3) snprintf(why, sizeof why, "takes <levels, ',' between them> <DM_<NAME>=<value>>");
                else if (!(m = detail_levels_of(p, f[1], why, sizeof why))) {}
                else if (!detail_env_key_ok(f[2]) || strlen(f[2]) >= sizeof p->env[0].kv) snprintf(why, sizeof why, "%s: a DM_<NAME>=<value> host switch (not DM_DETAIL*, DM_ROMFS_OVERLAY or a path the launcher sets)", f[2]);
                else if (p->nenv == DETAIL_MAX_ENV) snprintf(why, sizeof why, "more than %d detail_env rows", DETAIL_MAX_ENV);
                else { p->env[p->nenv].levels = m; snprintf(p->env[p->nenv].kv, sizeof p->env[0].kv, "%s", f[2]); p->nenv++; }
            } else if (!strcmp(f[0], "detail_zsdic")) {
                if (nf != 2 || !detail_path_ok(f[1]) || detail_has_glob(f[1], strlen(f[1]))) snprintf(why, sizeof why, "takes <the RomFS path of the pack of zstd dictionaries>");
                else if (*p->zsdic) snprintf(why, sizeof why, "named twice");
                else snprintf(p->zsdic, sizeof p->zsdic, "%s", f[1]);
            } else continue;   // not a detail row
            if (*why) { detail_say(log, ctx, "[RED] [detail] profile row \"%s\": %s; the row is left out", rows[r], why); red++; }
        }
    if (p->nlv == 1) { detail_say(log, ctx, "[RED] [detail] the profile has one detail_level only: nothing to choose"); red++; }
    return red;
}

// DM_DETAIL's value: level ids separated by '+' (a hidden level, such as a candidate's, is chosen this way). Returns the mask, 0 for the first level alone
// (as the game draws it), -1 with why on an unknown id
static int64_t detail_choose(const detail_profile *p, const char *s, char *why, size_t wn) {
    uint32_t m = 0; char b[256]; snprintf(b, sizeof b, "%s", s); char *save = NULL; int any = 0;
    for (char *t = strtok_r(b, "+", &save); t; t = strtok_r(NULL, "+", &save)) {
        int i = detail_level_index(p, t);
        if (i < 0) {
            snprintf(why, wn, "the profile has no level %s (it has:%s", t, p->nlv ? "" : " none");
            for (int k = 0; k < p->nlv; k++) { size_t l = strlen(why); snprintf(why + l, wn - l, " %s", p->lv[k].id); }
            size_t l = strlen(why); snprintf(why + l, wn - l, ")"); return -1;
        }
        any = 1; if (i) m |= 1u << i;
    }
    if (!any) { snprintf(why, wn, "no level named"); return -1; }
    return m;
}

// ---- BYML (Nintendo's binary YAML; versions 2 to 10, either byte order), edited in place ----

typedef struct { uint8_t *b; size_t n; int be; uint32_t keys; uint32_t nkeys; uint32_t root; } detail_byml;
static uint32_t detail_rd(const detail_byml *y, size_t o, int w) {   // w bytes (3 or 4) in the file's byte order; the caller checked the bounds
    uint32_t v = 0;
    for (int i = 0; i < w; i++) v |= (uint32_t)y->b[o + i] << (8 * (y->be ? w - 1 - i : i));
    return v;
}
static void detail_wr(detail_byml *y, size_t o, uint64_t v, int w) { for (int i = 0; i < w; i++) y->b[o + i] = (uint8_t)(v >> (8 * (y->be ? w - 1 - i : i))); }
static int detail_byml_open(detail_byml *y, uint8_t *b, size_t n, char *why, size_t wn) {
    memset(y, 0, sizeof *y); y->b = b; y->n = n;
    if (n < 16 || !((b[0] == 'Y' && b[1] == 'B') || (b[0] == 'B' && b[1] == 'Y'))) { snprintf(why, wn, "not a BYML file"); return 0; }
    y->be = b[0] == 'B';
    uint16_t v = y->be ? (uint16_t)(b[2] << 8 | b[3]) : (uint16_t)(b[3] << 8 | b[2]);
    if (v < 2 || v > 10) { snprintf(why, wn, "BYML version %u", v); return 0; }
    y->keys = detail_rd(y, 4, 4); y->root = detail_rd(y, 12, 4);
    if (y->keys) {
        if (y->keys > n - 8 || b[y->keys] != 0xC2) { snprintf(why, wn, "BYML key table at %#x is not a string table", y->keys); return 0; }
        y->nkeys = detail_rd(y, y->keys + 1, 3);
        if ((uint64_t)y->keys + 4 + 4ull * (y->nkeys + 1) > n) { snprintf(why, wn, "BYML key table runs past the file"); return 0; }
    }
    if (y->root && (y->root > n - 4)) { snprintf(why, wn, "BYML root at %#x is past the file", y->root); return 0; }
    return 1;
}
// Key i of the key table, NUL-terminated within the file, or NULL
static const char *detail_byml_key(const detail_byml *y, uint32_t i) {
    if (i >= y->nkeys) return NULL;
    uint64_t o = (uint64_t)y->keys + detail_rd(y, y->keys + 4 + 4 * i, 4);
    if (o >= y->n || !memchr(y->b + o, 0, y->n - o)) return NULL;
    return (const char *)y->b + o;
}
static const char *detail_type_name(int t) {
    switch (t) {
    case 0xA0: return "string"; case 0xA1: case 0xA2: return "binary"; case 0xC0: return "array"; case 0xC1: return "dictionary";
    case 0xD0: return "boolean"; case 0xD1: return "integer"; case 0xD2: return "float"; case 0xD3: return "unsigned integer";
    case 0xD4: return "64-bit integer"; case 0xD5: return "64-bit unsigned integer"; case 0xD6: return "double"; case 0xFF: return "null";
    default: return "node of an unknown type";
    }
}
// One value: its type and where its bytes are (an inline 4-byte slot, or for a 64-bit value the 8 bytes its slot points at)
static int detail_byml_apply(detail_byml *y, int t, size_t slot, const detail_param *q, const char *path, char *why, size_t wn, char *chg, size_t cn) {
    size_t at = slot; int w = 4;
    if (t == 0xD4 || t == 0xD5 || t == 0xD6) { at = detail_rd(y, slot, 4); w = 8; if (at > y->n - 8 || y->n < 8) { snprintf(why, wn, "%s: its 64-bit value is past the file", path); return 0; } }
    uint64_t raw = 0; for (int i = 0; i < w; i++) raw |= (uint64_t)y->b[at + i] << (8 * (y->be ? w - 1 - i : i));
    if (t == 0xD0) {
        if (q->kind != DETAIL_SET_BOOL) { snprintf(why, wn, "%s is a boolean: the edit must be =true or =false", path); return 0; }
        if (raw > 1) { snprintf(why, wn, "%s: boolean word %#llx", path, (unsigned long long)raw); return 0; }
        detail_wr(y, at, (uint64_t)q->b, 4); snprintf(chg, cn, "%s %s -> %s", path, raw ? "true" : "false", q->b ? "true" : "false"); return 1;
    }
    if (t != 0xD1 && t != 0xD2 && t != 0xD3 && t != 0xD4 && t != 0xD5 && t != 0xD6) { snprintf(why, wn, "%s is a %s, not a number or a boolean", path, detail_type_name(t)); return 0; }
    if (q->kind == DETAIL_SET_BOOL) { snprintf(why, wn, "%s is a %s: =true / =false is for a boolean", path, detail_type_name(t)); return 0; }
    double old;
    if (t == 0xD2) { uint32_t u = (uint32_t)raw; float f; memcpy(&f, &u, 4); old = f; }
    else if (t == 0xD6) { double d; memcpy(&d, &raw, 8); old = d; }
    else if (t == 0xD1) old = (int32_t)(uint32_t)raw;
    else if (t == 0xD4) old = (double)(int64_t)raw;
    else old = (double)raw;
    double v = q->kind == DETAIL_SCALE ? old * q->num : q->num;
    if (!isfinite(v)) { snprintf(why, wn, "%s: %g gives %g", path, old, v); return 0; }
    if (t == 0xD2) { float f = (float)v; uint32_t u; memcpy(&u, &f, 4); if (!isfinite(f)) { snprintf(why, wn, "%s: %g is out of a float's range", path, v); return 0; } detail_wr(y, at, u, 4); v = f; }
    else if (t == 0xD6) { uint64_t u; memcpy(&u, &v, 8); detail_wr(y, at, u, 8); }
    else {
        if (q->kind == DETAIL_SET_NUM && v != floor(v)) { snprintf(why, wn, "%s is a %s: %g is not a whole number", path, detail_type_name(t), v); return 0; }
        double r = q->kind == DETAIL_SCALE ? round(v) : v;
        double lo = t == 0xD1 ? -2147483648.0 : t == 0xD4 ? -9223372036854775808.0 : 0, hi = t == 0xD1 ? 2147483647.0 : t == 0xD3 ? 4294967295.0 : t == 0xD4 ? 9223372036854774784.0 : 18446744073709549568.0;
        if (r < lo || r > hi) { snprintf(why, wn, "%s: %g is out of a %s's range", path, r, detail_type_name(t)); return 0; }
        uint64_t u = (t == 0xD1 || t == 0xD4) ? (uint64_t)(int64_t)r : (uint64_t)r;
        detail_wr(y, at, u, w); v = r;
    }
    snprintf(chg, cn, "%s %.9g -> %.9g", path, old, v); return 1;
}
// A value the file stores, reached by a key path. The game's BYML writer stores equal values and containers once (Ocarina of Time's Graveyard_Adult: LodSetting and
// LodSetting_hh are one dictionary), so an edit in place changes every path that reaches the same bytes: id names the bytes (the slot, or for a 64-bit value the
// 8 bytes its slot points at), and an edit is made only when every path to them is asked for by a row with the same edit (detail_byml_edit)
typedef struct { uint64_t id; size_t slot; int type; char path[256]; } detail_ref;
typedef struct { detail_ref *v; int n, cap; unsigned visits; } detail_refs;
static int detail_refs_add(detail_refs *r, const detail_byml *y, size_t slot, int t, const char *path) {
    if (r->n == r->cap) { int c = r->cap ? r->cap * 2 : 64; detail_ref *v = realloc(r->v, (size_t)c * sizeof *v); if (!v) return 0; r->v = v; r->cap = c; }
    detail_ref *x = &r->v[r->n++]; x->slot = slot; x->type = t; snprintf(x->path, sizeof x->path, "%s", path);
    x->id = (t == 0xD4 || t == 0xD5 || t == 0xD6) ? (1ull << 32) | detail_rd(y, slot, 4) : (uint64_t)slot;
    return 1;
}
// The values under the node at o whose key paths match the components [c, ...) (c NULL: every value), each with its path
static int detail_byml_collect(const detail_byml *y, uint32_t o, const char *c, char *path, size_t pn, int depth, detail_refs *r, char *why, size_t wn) {
    if (depth > 32 || ++r->visits > 200000) { snprintf(why, wn, "%s: nested deeper than 32 or too many shared nodes", *path ? path : "the root"); return 0; }
    if (y->n < 4 || o > y->n - 4) { snprintf(why, wn, "%s: a node past the file", *path ? path : "the root"); return 0; }
    const char *dot = c ? strchr(c, '.') : NULL; char comp[256] = "*";
    if (c) { size_t cl = dot ? (size_t)(dot - c) : strlen(c); memcpy(comp, c, cl); comp[cl] = 0; }
    int t = y->b[o], last = c && !dot; uint32_t cnt = detail_rd(y, o + 1, 3); size_t pl = strlen(path);
    if (t != 0xC0 && t != 0xC1) { snprintf(why, wn, "%s is a %s (type %#x): only dictionaries (0xc1) and arrays (0xc0) are walked", *path ? path : "the root", detail_type_name(t), t); return 0; }
    uint64_t vals = t == 0xC1 ? (uint64_t)o + 4 : (uint64_t)o + 4 + ((cnt + 3) & ~3u), step = t == 0xC1 ? 8 : 4;
    if (vals + step * cnt > y->n) { snprintf(why, wn, "%s: a %s past the file", *path ? path : "the root", detail_type_name(t)); return 0; }
    for (uint32_t i = 0; i < cnt; i++) {
        size_t slot; int et; char name[256];
        if (t == 0xC1) {
            size_t e = (size_t)vals + 8 * (size_t)i; const char *k = detail_byml_key(y, detail_rd(y, e, 3));
            if (!k) { snprintf(why, wn, "%s: a key index outside the key table", *path ? path : "the root"); return 0; }
            snprintf(name, sizeof name, "%s", k); et = y->b[e + 3]; slot = e + 4;
        } else { snprintf(name, sizeof name, "%u", i); et = y->b[o + 4 + i]; slot = (size_t)vals + 4 * (size_t)i; }
        if (fnmatch(comp, name, 0)) continue;
        snprintf(path + pl, pn - pl, "%s%s", pl ? "." : "", name);
        int container = et == 0xC0 || et == 0xC1;
        if (container && (!c || dot)) { if (!detail_byml_collect(y, detail_rd(y, slot, 4), dot ? dot + 1 : NULL, path, pn, depth + 1, r, why, wn)) return 0; }
        else if (last && container) { snprintf(why, wn, "%s is a %s, not a value", path, detail_type_name(et)); return 0; }
        else if (!c || last) {
            if ((et == 0xD4 || et == 0xD5 || et == 0xD6) && (y->n < 8 || detail_rd(y, slot, 4) > y->n - 8)) { snprintf(why, wn, "%s: its 64-bit value is past the file", path); return 0; }
            if (!detail_refs_add(r, y, slot, et, path)) { snprintf(why, wn, "out of memory"); return 0; }
        }   // (a value where the path goes on: not this path)
        path[pl] = 0;
    }
    return 1;
}
// Whether a key path pattern ("LodSetting_hh.Dist_*") matches a path ("LodSetting_hh.Dist_LowTree"), component by component
static int detail_key_match(const char *pat, const char *path) {
    for (;;) {
        const char *a = strchr(pat, '.'), *b = strchr(path, '.'); char x[256], z[256];
        size_t la = a ? (size_t)(a - pat) : strlen(pat), lb = b ? (size_t)(b - path) : strlen(path);
        if (la >= sizeof x || lb >= sizeof z) return 0;
        memcpy(x, pat, la); x[la] = 0; memcpy(z, path, lb); z[lb] = 0;
        if (fnmatch(x, z, 0)) return 0;
        if (!a || !b) return !a && !b;
        pat = a + 1; path = b + 1;
    }
}
static int detail_same_edit(const detail_param *a, const detail_param *b) { return a->kind == b->kind && a->num == b->num && a->b == b->b; }
// The values a file's earlier rows edited (their ids, offset by base for a pack member, and the row that did): each stored value is edited once
typedef struct { uint64_t id; const detail_param *by; } detail_did;
typedef struct { detail_did *v; int n, cap; } detail_done;

// Applies one row's edit to a BYML file in buf (in place). peers: every row that applies to this file (q among them). Returns 1 with *found values the key path
// reaches (0 when the file has no such key) and *applied of them edited now (a value an earlier row with the same edit reached through a shared node is not
// edited twice); 0 with why on a malformed file, an edit that does not fit a value's type, a value the file shares with a path no row asks the same edit for, or
// two rows asking different edits of one value (the caller then keeps the game's file)
static int detail_byml_edit(uint8_t *buf, size_t n, const detail_param *q, const detail_param *const *peers, int npeers, detail_done *done, uint64_t base,
                            unsigned *found, unsigned *applied, char *why, size_t wn, char *chg, size_t cn) {
    detail_byml y; *found = *applied = 0; int ok = 0;
    if (!detail_byml_open(&y, buf, n, why, wn)) return 0;
    if (!y.root) return 1;   // an empty document has no keys
    detail_refs t = {0}, all = {0}; char path[512] = "";
    if (!detail_byml_collect(&y, y.root, q->key, path, sizeof path, 0, &t, why, wn)) goto out;
    if (!t.n) { ok = 1; goto out; }
    path[0] = 0;
    if (!detail_byml_collect(&y, y.root, NULL, path, sizeof path, 0, &all, why, wn)) goto out;
    for (int i = 0; i < t.n; i++)
        for (int j = 0; j < all.n; j++) {
            if (all.v[j].id != t.v[i].id) continue;
            int asked = 0;
            for (int k = 0; k < npeers && !asked; k++) asked = detail_same_edit(peers[k], q) && detail_key_match(peers[k]->key, all.v[j].path);
            if (!asked) { snprintf(why, wn, "%s is stored once with %s (equal values or containers are shared): no row asks the same edit for %s", t.v[i].path, all.v[j].path, all.v[j].path); goto out; }
        }
    // all checked first: a refused edit leaves the file as it was
    for (int i = 0; i < t.n; i++) {
        uint64_t id = base + t.v[i].id; int dup = 0;
        for (int k = 0; k < done->n && !dup; k++) if (done->v[k].id == id) {
            if (!detail_same_edit(done->v[k].by, q)) { snprintf(why, wn, "%s: two rows ask different edits of it (%s, %s)", t.v[i].path, strrchr(done->v[k].by->text, ' ') + 1, strrchr(q->text, ' ') + 1); goto out; }
            dup = 1;
        }
        for (int k = 0; k < i && !dup; k++) dup = t.v[k].id == t.v[i].id;
        (*found)++;
        if (dup) continue;
        size_t l = strlen(chg); if (l && l + 2 < cn) { chg[l++] = ';'; chg[l++] = ' '; chg[l] = 0; }
        if (!detail_byml_apply(&y, t.v[i].type, t.v[i].slot, q, t.v[i].path, why, wn, chg + l, cn - l)) goto out;
        if (done->n == done->cap) { int c = done->cap ? done->cap * 2 : 64; detail_did *v = realloc(done->v, (size_t)c * sizeof *v); if (!v) { snprintf(why, wn, "out of memory"); goto out; } done->v = v; done->cap = c; }
        done->v[done->n].id = id; done->v[done->n].by = q; done->n++;
        (*applied)++;
    }
    ok = 1;
out:
    free(t.v); free(all.v);
    return ok;
}

// ---- SARC ----

typedef struct { char name[256]; uint32_t start, end; } detail_member;   // [start, end) in the SARC
static uint32_t detail_u(const uint8_t *b, size_t o, int w, int be) { uint32_t v = 0; for (int i = 0; i < w; i++) v |= (uint32_t)b[o + i] << (8 * (be ? w - 1 - i : i)); return v; }
// The members of a SARC (malloc'd array in *out, its count returned), -1 with why
static int detail_sarc(const uint8_t *b, size_t n, detail_member **out, char *why, size_t wn) {
    *out = NULL;
    if (n < 0x20 || memcmp(b, "SARC", 4)) { snprintf(why, wn, "not a SARC"); return -1; }
    int be = b[6] == 0xFE && b[7] == 0xFF;
    if (!be && !(b[6] == 0xFF && b[7] == 0xFE)) { snprintf(why, wn, "SARC byte order mark %02x%02x", b[6], b[7]); return -1; }
    uint32_t hl = detail_u(b, 4, 2, be), size = detail_u(b, 8, 4, be), data = detail_u(b, 12, 4, be);
    if (hl != 0x14 || size != n || data > n) { snprintf(why, wn, "SARC header (header %u, file size %u of %zu, data at %u)", hl, size, n, data); return -1; }
    if (memcmp(b + 0x14, "SFAT", 4)) { snprintf(why, wn, "SARC without SFAT"); return -1; }
    uint32_t cnt = detail_u(b, 0x1A, 2, be); uint64_t nodes = 0x20, names = nodes + 16ull * cnt;
    if (names + 8 > n || memcmp(b + names, "SFNT", 4)) { snprintf(why, wn, "SARC's SFNT is missing"); return -1; }
    uint64_t pool = names + detail_u(b, (size_t)names + 4, 2, be);
    detail_member *m = calloc(cnt ? cnt : 1, sizeof *m); if (!m) { snprintf(why, wn, "out of memory"); return -1; }
    for (uint32_t i = 0; i < cnt; i++) {
        size_t e = (size_t)(nodes + 16ull * i); uint32_t attr = detail_u(b, e + 4, 4, be), s = detail_u(b, e + 8, 4, be), en = detail_u(b, e + 12, 4, be);
        if (s > en || (uint64_t)data + en > n) { free(m); snprintf(why, wn, "SARC member %u runs past the file", i); return -1; }
        m[i].start = data + s; m[i].end = data + en;
        if (attr >> 24) {
            uint64_t no = pool + 4ull * (attr & 0xFFFFFF);
            if (no >= n || !memchr(b + no, 0, n - (size_t)no)) { free(m); snprintf(why, wn, "SARC member %u's name runs past the file", i); return -1; }
            snprintf(m[i].name, sizeof m[i].name, "%s", (const char *)b + no);
        }
    }
    *out = m; return (int)cnt;
}

// ---- zstd frames ----

typedef struct { size_t hsize; uint64_t fcs; int has_fcs; uint32_t dictid; int did_size; int single; uint64_t window; int checksum; uint8_t fhd, wd; } detail_zhead;
static int detail_zstd_head(const uint8_t *b, size_t n, detail_zhead *h, char *why, size_t wn) {
    memset(h, 0, sizeof *h);
    if (n < 6 || b[0] != 0x28 || b[1] != 0xB5 || b[2] != 0x2F || b[3] != 0xFD) { snprintf(why, wn, "not a zstd frame"); return 0; }
    uint8_t fhd = b[4]; h->fhd = fhd; int fcsf = fhd >> 6, dic = fhd & 3; h->single = fhd >> 5 & 1; h->checksum = fhd >> 2 & 1;
    if (fhd & 8) { snprintf(why, wn, "zstd frame header with its reserved bit set"); return 0; }
    static const int ds[4] = {0, 1, 2, 4}; int fs = fcsf == 0 ? (h->single ? 1 : 0) : fcsf == 1 ? 2 : fcsf == 2 ? 4 : 8;
    size_t o = 5;
    if (!h->single) { if (o >= n) goto short_; h->wd = b[o]; int e = b[o] >> 3, m = b[o] & 7; uint64_t base = 1ull << (10 + e); h->window = base + base / 8 * m; o++; }
    if (o + ds[dic] + fs > n) goto short_;
    h->did_size = ds[dic]; for (int i = 0; i < ds[dic]; i++) h->dictid |= (uint32_t)b[o + i] << (8 * i); o += ds[dic];
    if (fs) { h->has_fcs = 1; for (int i = 0; i < fs; i++) h->fcs |= (uint64_t)b[o + i] << (8 * i); if (fs == 2) h->fcs += 256; o += fs; }
    if (h->single) h->window = h->fcs;
    h->hsize = o; return 1;
short_:
    snprintf(why, wn, "a zstd frame header cut short"); return 0;
}
// content as one zstd frame of raw blocks, with the source frame's header layout (its content size, window, and its dictionary ID unless dictid_mode is NONE);
// the checksum flag is cleared (raw blocks carry no checksum). NULL when the content does not fit the source header's size fields
static uint8_t *detail_zstd_raw(const detail_zhead *src, const uint8_t *data, size_t n, int dictid_mode, size_t *outn) {
    if (!src->has_fcs || src->fcs != n) return NULL;
    size_t block = src->window < (128u << 10) ? (size_t)src->window : (128u << 10); if (!block) block = 1;
    size_t nb = n ? (n + block - 1) / block : 1;
    uint8_t *o = malloc(18 + n + 3 * nb); if (!o) return NULL;
    size_t k = 0; o[k++] = 0x28; o[k++] = 0xB5; o[k++] = 0x2F; o[k++] = 0xFD;
    int keep = dictid_mode == DETAIL_DICTID_KEEP && src->did_size;
    uint8_t fhd = (uint8_t)(src->fhd & ~0x07);   // no checksum, no dictionary ID field (set again below)
    if (keep) fhd |= (uint8_t)(src->did_size == 1 ? 1 : src->did_size == 2 ? 2 : 3);
    o[k++] = fhd;
    if (!src->single) o[k++] = src->wd;
    if (keep) for (int i = 0; i < src->did_size; i++) o[k++] = (uint8_t)(src->dictid >> (8 * i));
    int fcsf = fhd >> 6, fs = fcsf == 0 ? (src->single ? 1 : 0) : fcsf == 1 ? 2 : fcsf == 2 ? 4 : 8;
    uint64_t fv = fs == 2 ? n - 256 : n;
    for (int i = 0; i < fs; i++) o[k++] = (uint8_t)(fv >> (8 * i));
    size_t done = 0;
    do {
        size_t len = n - done < block ? n - done : block; int last = done + len == n;
        uint32_t bh = (uint32_t)last | (0u << 1) | (uint32_t)len << 3;   // block type 0: raw
        o[k++] = (uint8_t)bh; o[k++] = (uint8_t)(bh >> 8); o[k++] = (uint8_t)(bh >> 16);
        memcpy(o + k, data + done, len); k += len; done += len;
    } while (done < n);
    *outn = k; return o;
}

// ---- Digest (FNV-1a, 64 bits): of the rows, the switches and the source files' bytes ----
static uint64_t detail_fnv(uint64_t h, const void *p, size_t n) { const uint8_t *b = p; for (size_t i = 0; i < n; i++) { h ^= b[i]; h *= 0x100000001b3ull; } return h; }

// ---- Files ----

static int detail_rm_tree(const char *p) {
    struct stat st; if (lstat(p, &st)) return errno == ENOENT ? 0 : -1;
    if (S_ISDIR(st.st_mode)) {
        DIR *d = opendir(p); if (!d) return -1;
        struct dirent *e; int r = 0;
        while ((e = readdir(d))) {
            if (!strcmp(e->d_name, ".") || !strcmp(e->d_name, "..")) continue;
            char c[1200]; snprintf(c, sizeof c, "%s/%s", p, e->d_name); if (detail_rm_tree(c)) r = -1;
        }
        closedir(d);
        return r || rmdir(p) ? -1 : 0;
    }
    return unlink(p);
}
static int detail_mkdirs(const char *dir) {
    char b[1200]; snprintf(b, sizeof b, "%s", dir);
    for (char *c = b + 1; *c; c++) if (*c == '/') { *c = 0; if (mkdir(b, 0755) && errno != EEXIST) return -1; *c = '/'; }
    return mkdir(b, 0755) && errno != EEXIST ? -1 : 0;
}
static int detail_write(const char *root, const char *rel, const uint8_t *b, size_t n) {
    char p[1200]; snprintf(p, sizeof p, "%s/%s", root, rel);
    char *sl = strrchr(p, '/'); *sl = 0; int r = detail_mkdirs(p); *sl = '/';
    if (r) return -1;
    int fd = open(p, O_WRONLY | O_CREAT | O_TRUNC, 0644); if (fd < 0) return -1;
    for (size_t k = 0; k < n;) { ssize_t w = write(fd, b + k, n - k); if (w <= 0) { if (w < 0 && errno == EINTR) continue; close(fd); return -1; } k += (size_t)w; }
    return close(fd);
}

// ---- The build ----

// The player's game files and the decoder, given by the caller
typedef struct {
    void *ctx;
    // a whole RomFS file (malloc'd, its size in *n); NULL with why
    uint8_t *(*read)(void *ctx, const char *path, size_t *n, char *why, size_t wn);
    // the files (not folders) of a RomFS folder, each passed to cb; 0 when the folder does not exist
    int (*list)(void *ctx, const char *dir, void (*cb)(void *u, const char *name), void *u);
    // one zstd frame decoded with a dictionary's content (NULL: none) into exactly cap bytes (malloc'd); NULL with why
    uint8_t *(*zdecode)(void *ctx, const uint8_t *src, size_t n, const uint8_t *dict, size_t dn, size_t cap, char *why, size_t wn);
    detail_log_fn log;
} detail_io;

typedef struct { char path[256]; uint8_t *b; size_t n; } detail_src;
typedef struct { detail_src *v; int n, cap; } detail_srcs;
static detail_src *detail_src_get(detail_srcs *s, const detail_io *io, const char *path, char *why, size_t wn) {
    for (int i = 0; i < s->n; i++) if (!strcmp(s->v[i].path, path)) return &s->v[i];
    if (s->n == s->cap) { int c = s->cap ? s->cap * 2 : 64; detail_src *v = realloc(s->v, (size_t)c * sizeof *v); if (!v) { snprintf(why, wn, "out of memory"); return NULL; } s->v = v; s->cap = c; }
    detail_src *d = &s->v[s->n]; memset(d, 0, sizeof *d); snprintf(d->path, sizeof d->path, "%s", path);
    if (!(d->b = io->read(io->ctx, path, &d->n, why, wn))) return NULL;
    s->n++; return d;
}
static void detail_srcs_free(detail_srcs *s) { for (int i = 0; i < s->n; i++) free(s->v[i].b); free(s->v); memset(s, 0, sizeof *s); }

typedef struct { char (*names)[256]; int n, cap; const char *glob; } detail_names;
static void detail_name_cb(void *u, const char *name) {
    detail_names *l = u; if (fnmatch(l->glob, name, 0)) return;
    if (l->n == l->cap) { int c = l->cap ? l->cap * 2 : 64; void *v = realloc(l->names, (size_t)c * 256); if (!v) return; l->names = v; l->cap = c; }
    snprintf(l->names[l->n++], 256, "%s", name);
}
static int detail_cmp_name(const void *a, const void *b) { return strcmp(a, b); }

// An edited file of the overlay: a loose file's bytes, or a pack's SARC
// rowvals / rowfiles: per row, the values it edited in this file and the files (1, or the pack's members) it edited, added to the row's counts only when the file is written
typedef struct { char path[256]; uint8_t *b; size_t n; int pack, bad; detail_zhead zh; uint8_t *dict; size_t dn; unsigned edits; char *chg; size_t chgn; unsigned *rowvals, *rowfiles; detail_done done;
                 uint8_t *orig; uint32_t *badm; int nbad; struct { uint32_t start; int row; unsigned applied; } *mlog; int nmlog; } detail_out;   // orig: a pack's SARC as decoded; badm: members left as they were; mlog: each member's edits per row

static void detail_note(detail_out *o, const char *what) {   // the values changed (old -> new), one line per file or member, for the PC check's report
    size_t l = strlen(what); if (!l) return;
    char *c = realloc(o->chg, o->chgn + l + 2); if (!c) return;
    if (o->chgn) c[o->chgn++] = '\n';
    memcpy(c + o->chgn, what, l + 1); o->chg = c; o->chgn += l;
}

// Whether a row names a loose file (path) or, for a pack row, a member (member) of the pack at path
static int detail_row_names(const detail_param *q, const char *path, const char *member) {
    if (q->pack != !!member) return 0;
    if (member) return !strcmp(q->path, path) && !fnmatch(q->member, member, FNM_PATHNAME);
    const char *a = strrchr(q->path, '/'), *b = strrchr(path, '/');
    size_t la = a ? (size_t)(a - q->path) : 0, lb = b ? (size_t)(b - path) : 0;
    return la == lb && !strncmp(q->path, path, la) && !fnmatch(a ? a + 1 : q->path, b ? b + 1 : path, 0);
}
// The rows selected: those of the chosen levels, and of the copy (loose files, pack members or both) DM_DETAIL_COPY asks for
static int detail_selected(const detail_param *q, uint32_t mask, int copy) {
    return (q->levels & mask) && !(copy == DETAIL_COPY_LOOSE && q->pack) && !(copy == DETAIL_COPY_PACK && !q->pack);
}

// The build's log: lines go to the caller's log; once recording is on, the red lines are also kept for the manifest (said again when the folder is used later)
typedef struct { detail_log_fn log; void *ctx; char *buf; size_t n; int on; } detail_rec;
static void detail_rec_log(void *c, const char *line) {
    detail_rec *r = c; r->log(r->ctx, line);
    if (!r->on || strncmp(line, "[RED] ", 6)) return;
    size_t l = strlen(line); char *b = realloc(r->buf, r->n + l + 2); if (!b) return;
    memcpy(b + r->n, line, l); b[r->n + l] = '\n'; b[r->n + l + 1] = 0; r->buf = b; r->n += l + 1;
}
// Builds (or finds) the overlay for the levels in mask. Returns 1 with its folder in outdir (empty when the levels edit no file), 0 when there is no overlay to use
// (the game then starts as the game draws it; the reason is a red line). report: when not NULL, the values changed, file by file (the PC check's report)
static int detail_build(detail_profile *p, uint32_t mask, const char *name, int copy, int dictid_mode, const char *temp, const detail_io *io, char *outdir, size_t on, FILE *report) {
    char why[600] = "", final[1200] = "", tmp[1300] = "", tmpfs[1400] = "", manifest[1300] = "", lockp[1300] = ""; int ok = 0, nsel = 0, red = 0, lockfd = -1;
    detail_rec rec = {io->log, io->ctx, NULL, 0, 0};
    *outdir = 0;
    detail_srcs src = {0}; detail_out *outs = NULL; int nout = 0;
    for (int i = 0; i < p->npar; i++) { p->par[i].files = p->par[i].found = p->par[i].hit_files = p->par[i].values = 0; if (detail_selected(&p->par[i], mask, copy)) nsel++; }
    if (!nsel) return 1;   // the levels set switches only
    if (!temp || !*temp) { detail_say(detail_rec_log, &rec, "[RED] [detail] %s: DM_TEMP is not set: no overlay, the game starts as the game draws it (Full)", name); return 0; }
    outs = calloc((size_t)p->npar * 2 + 64, sizeof *outs);
    int outcap = p->npar * 2 + 64;
    if (!outs) { detail_say(detail_rec_log, &rec, "[RED] [detail] %s: out of memory: the game starts as the game draws it (Full)", name); return 0; }
    // 1. The files each row names, read once; the digest of the rows, the switches and their bytes
    uint64_t h = detail_fnv(0xcbf29ce484222325ull, DETAIL_VERSION, sizeof DETAIL_VERSION);
    char sw[64]; snprintf(sw, sizeof sw, "copy=%d dictid=%d", copy, dictid_mode); h = detail_fnv(h, sw, strlen(sw) + 1);
    typedef struct { int row; char path[256]; } job;
    job *jobs = NULL; int njob = 0, jcap = 0, packs = 0;
    for (int i = 0; i < p->npar; i++) {
        detail_param *q = &p->par[i]; if (!detail_selected(q, mask, copy)) continue;
        h = detail_fnv(h, q->text, strlen(q->text) + 1); h = detail_fnv(h, &q->levels, sizeof q->levels);
        detail_names l = {0};
        if (q->pack) { l.names = malloc(256); if (l.names) { snprintf(l.names[0], 256, "%s", q->path); l.n = l.cap = 1; } packs = 1; }
        else {
            char dir[256]; snprintf(dir, sizeof dir, "%s", q->path); char *sl = strrchr(dir, '/'); const char *g = sl ? sl + 1 : dir;
            char glob[256]; snprintf(glob, sizeof glob, "%s", g); if (sl) *sl = 0; else dir[0] = 0;
            l.glob = glob;
            if (!io->list(io->ctx, dir, detail_name_cb, &l)) l.n = 0;
            if (l.n) qsort(l.names, (size_t)l.n, 256, detail_cmp_name);
            for (int k = 0; k < l.n; k++) { char full[256]; snprintf(full, sizeof full, "%s%s%s", dir, *dir ? "/" : "", l.names[k]); snprintf(l.names[k], 256, "%s", full); }
        }
        if (!l.n) { detail_say(detail_rec_log, &rec, "[RED] [detail] row \"%s\": no file matches %s; the row does nothing", q->text, q->path); red++; }
        for (int k = 0; k < l.n; k++) {
            if (njob == jcap) { jcap = jcap ? jcap * 2 : 256; job *v = realloc(jobs, (size_t)jcap * sizeof *v); if (!v) { free(l.names); goto oom; } jobs = v; }
            jobs[njob].row = i; snprintf(jobs[njob].path, sizeof jobs[njob].path, "%s", l.names[k]); njob++;
            detail_src *s = detail_src_get(&src, io, l.names[k], why, sizeof why);
            if (!s) { detail_say(detail_rec_log, &rec, "[RED] [detail] %s: cannot be read (%s): the rows that name it do nothing", l.names[k], why); red++; *why = 0; njob--; continue; }
        }
        free(l.names);
    }
    const uint8_t *dict_pack = NULL; size_t dict_pack_n = 0; int need_dict = 0;
    for (int i = 0; packs && i < src.n; i++) {   // a pack whose frame names a dictionary: the pack of dictionaries is read (and is in the digest)
        detail_zhead zh0; char w0[100];
        for (int j = 0; j < njob; j++) if (p->par[jobs[j].row].pack && !strcmp(jobs[j].path, src.v[i].path) && detail_zstd_head(src.v[i].b, src.v[i].n, &zh0, w0, sizeof w0) && zh0.dictid) need_dict = 1;
    }
    if (need_dict && *p->zsdic) {
        detail_src *s = detail_src_get(&src, io, p->zsdic, why, sizeof why);
        if (s) { dict_pack = s->b; dict_pack_n = s->n; } else { detail_say(detail_rec_log, &rec, "[RED] [detail] the dictionaries %s cannot be read (%s)", p->zsdic, why); red++; *why = 0; }
    }
    {   // the sources in path order: the digest does not depend on the order the rows name them
        int *ord = malloc(sizeof(int) * (size_t)(src.n ? src.n : 1)); if (!ord) goto oom;
        for (int i = 0; i < src.n; i++) ord[i] = i;
        for (int i = 1; i < src.n; i++) for (int j = i; j > 0 && strcmp(src.v[ord[j - 1]].path, src.v[ord[j]].path) > 0; j--) { int t = ord[j]; ord[j] = ord[j - 1]; ord[j - 1] = t; }
        for (int i = 0; i < src.n; i++) { detail_src *s = &src.v[ord[i]]; h = detail_fnv(h, s->path, strlen(s->path) + 1); h = detail_fnv(h, &s->n, sizeof s->n); h = detail_fnv(h, s->b, s->n); }
        free(ord);
    }
    snprintf(final, sizeof final, "%s/detail-%s-%016llx", temp, name, (unsigned long long)h);
    snprintf(tmp, sizeof tmp, "%s.tmp", final); snprintf(tmpfs, sizeof tmpfs, "%s/romfs", tmp);
    snprintf(manifest, sizeof manifest, "%s/detail.txt", final);
    // One build at a time: a second start waits here, then finds the first one's folder (no lock file, as on a read-only folder: the writes below say why)
    snprintf(lockp, sizeof lockp, "%s/.lock", temp);
    if (!detail_mkdirs(temp) && (lockfd = open(lockp, O_RDWR | O_CREAT, 0644)) >= 0) while (flock(lockfd, LOCK_EX) && errno == EINTR) {}
    // 2. An overlay already built for these rows and files is used as it is (its manifest is written last, so a folder without it was cut off); its build's red
    //    lines (the manifest's other lines) are said again
    {
        FILE *m = fopen(manifest, "r");
        if (m) {
            char *all = NULL; size_t an = 0, k; char chunk[4096];
            while ((k = fread(chunk, 1, sizeof chunk, m)) > 0) { char *g = realloc(all, an + k + 1); if (!g) { free(all); all = NULL; break; } all = g; memcpy(all + an, chunk, k); an += k; all[an] = 0; }
            fclose(m);
            char *nl = all ? strchr(all, '\n') : NULL;
            if (nl && nl > all) {
                *nl = 0; char dir[1300]; struct stat st; snprintf(dir, sizeof dir, "%s/romfs", final);
                if (!stat(dir, &st) && S_ISDIR(st.st_mode)) {
                    for (char *ln = nl + 1; *ln;) {
                        char *e2 = strchr(ln, '\n'); if (e2) *e2 = 0;
                        if (!strncmp(ln, "[RED] ", 6)) io->log(io->ctx, ln);
                        if (!e2) break;
                        ln = e2 + 1;
                    }
                    detail_say(detail_rec_log, &rec, "[detail] %s: overlay %s (made at an earlier start from the same rows and files): %s", name, dir, all);
                    snprintf(outdir, on, "%s", dir); ok = 1; free(all); goto older;
                }
            }
            free(all);
        }
    }
    rec.on = 1;   // from here on the red lines go into the manifest too
    if (detail_rm_tree(final) || detail_rm_tree(tmp)) { detail_say(detail_rec_log, &rec, "[RED] [detail] %s: cannot remove the earlier, partial %s (errno %d): the game starts as the game draws it (Full)", name, final, errno); goto clean; }
    // 3. The edits, file by file, in the rows' order
    for (int j = 0; j < njob; j++) {
        detail_param *q = &p->par[jobs[j].row]; detail_src *s = NULL;
        for (int k = 0; k < src.n; k++) if (!strcmp(src.v[k].path, jobs[j].path)) s = &src.v[k];
        if (!s) continue;
        detail_out *o = NULL;
        for (int k = 0; k < nout; k++) if (!strcmp(outs[k].path, jobs[j].path) && outs[k].pack == q->pack) o = &outs[k];
        if (!o) {
            if (nout == outcap) goto oom;
            o = &outs[nout]; memset(o, 0, sizeof *o); snprintf(o->path, sizeof o->path, "%s", jobs[j].path); o->pack = q->pack;
            if (!(o->rowvals = calloc((size_t)p->npar, sizeof *o->rowvals)) || !(o->rowfiles = calloc((size_t)p->npar, sizeof *o->rowfiles))) { nout++; goto oom; }
            if (!q->pack) { if (!(o->b = malloc(s->n ? s->n : 1))) goto oom; memcpy(o->b, s->b, s->n); o->n = s->n; }
            else {   // the pack's SARC: decoded with the dictionary its frame names
                const uint8_t *dict = NULL; size_t dn = 0; int bad = 0;
                if (s->n >= 4 && !memcmp(s->b, "SARC", 4)) { o->b = malloc(s->n); if (!o->b) goto oom; memcpy(o->b, s->b, s->n); o->n = s->n; o->zh.hsize = 0; }
                else if (!detail_zstd_head(s->b, s->n, &o->zh, why, sizeof why)) bad = 1;
                else if (!o->zh.has_fcs || o->zh.fcs > (1ull << 31)) { snprintf(why, sizeof why, "its zstd frame does not say its content size"); bad = 1; }
                else {
                    if (o->zh.dictid) {   // the dictionary whose ID the frame names, from the pack of dictionaries (each a SARC member in zstd's dictionary format)
                        uint8_t *dsarc = NULL; detail_zhead dz; detail_member *dm = NULL; int nd = -1;
                        if (!dict_pack) { snprintf(why, sizeof why, "its frame names dictionary %u and %s", o->zh.dictid, *p->zsdic ? "the dictionaries cannot be read" : "the profile has no detail_zsdic row"); bad = 1; }
                        else if (dict_pack_n >= 4 && !memcmp(dict_pack, "SARC", 4)) nd = detail_sarc(dict_pack, dict_pack_n, &dm, why, sizeof why);
                        else if (!detail_zstd_head(dict_pack, dict_pack_n, &dz, why, sizeof why) || !dz.has_fcs || dz.fcs > (1ull << 31)) { snprintf(why, sizeof why, "the dictionaries %s are not one zstd frame with its size", p->zsdic); bad = 1; }
                        else if (!(dsarc = io->zdecode(io->ctx, dict_pack, dict_pack_n, NULL, 0, (size_t)dz.fcs, why, sizeof why))) bad = 1;
                        else nd = detail_sarc(dsarc, (size_t)dz.fcs, &dm, why, sizeof why);
                        const uint8_t *db = dsarc ? dsarc : dict_pack;
                        for (int k = 0; k < nd && !dict; k++)
                            if (dm[k].end - dm[k].start >= 8 && detail_u(db, dm[k].start, 4, 0) == 0xEC30A437u && detail_u(db, dm[k].start + 4, 4, 0) == o->zh.dictid) {
                                dn = dm[k].end - dm[k].start; uint8_t *c = malloc(dn); if (c) { memcpy(c, db + dm[k].start, dn); dict = c; }
                            }
                        if (!bad && !dict) { snprintf(why, sizeof why, "its frame names dictionary %u, which %s does not have", o->zh.dictid, p->zsdic); bad = 1; }
                        free(dm); free(dsarc);
                    }
                    if (!bad && !(o->b = io->zdecode(io->ctx, s->b, s->n, dict, dn, (size_t)o->zh.fcs, why, sizeof why))) bad = 1;
                    if (!bad) { o->n = (size_t)o->zh.fcs; if (!(o->orig = malloc(o->n))) goto oom; memcpy(o->orig, o->b, o->n); }
                    o->dict = (uint8_t *)dict; o->dn = dn;   // kept: the written pack is decoded back with it
                }
                detail_member *mm = NULL;
                if (!bad && detail_sarc(o->b, o->n, &mm, why, sizeof why) < 0) bad = 1;
                free(mm);
                if (bad) {
                    detail_say(detail_rec_log, &rec, "[RED] [detail] %s: %s: the pack stays the game's own", jobs[j].path, why); red++; *why = 0;
                    free(o->b); o->b = NULL; o->n = 0; o->bad = 1; nout++;   // kept, marked: its other rows are skipped below
                    continue;
                }
            }
            nout++;
        }
        if (o->bad) continue;   // a pack that could not be decoded, or a file an edit failed on (said once)
        if (!q->pack) {   // a loose file: the edit on a copy, kept only when it succeeds
            uint8_t *c = malloc(o->n ? o->n : 1); if (!c) goto oom; memcpy(c, o->b, o->n);
            unsigned hits = 0, applied = 0; char chg[4096] = ""; const detail_param *peers[DETAIL_MAX_PARAMS]; int np = 0;
            for (int i = 0; i < p->npar; i++) if (detail_selected(&p->par[i], mask, copy) && detail_row_names(&p->par[i], o->path, NULL)) peers[np++] = &p->par[i];
            q->files++;
            if (!detail_byml_edit(c, o->n, q, peers, np, &o->done, 0, &hits, &applied, why, sizeof why, chg, sizeof chg)) {
                detail_say(detail_rec_log, &rec, "[RED] [detail] %s: %s (row \"%s\"): the file stays the game's own", o->path, why, q->text); red++; *why = 0;
                free(c); free(o->b); o->b = NULL; o->bad = 1; q->found++; continue;   // (said: not "none has the key" again)
            }
            if (hits) { q->found++; o->rowfiles[jobs[j].row]++; o->rowvals[jobs[j].row] += applied; o->edits += applied; detail_note(o, chg); }
            memcpy(o->b, c, o->n); free(c);
        } else {   // pack members
            detail_member *mm = NULL; int nm = detail_sarc(o->b, o->n, &mm, why, sizeof why), matched = 0;
            for (int k = 0; k < nm; k++) {
                if (fnmatch(q->member, mm[k].name, FNM_PATHNAME)) continue;
                matched++;
                int skip = 0; for (int i = 0; i < o->nbad; i++) skip |= o->badm[i] == mm[k].start;
                if (skip) { q->files++; q->found++; continue; }   // a member an earlier row failed on stays the game's own (said then)
                size_t ml = mm[k].end - mm[k].start; uint8_t *c = malloc(ml ? ml : 1); if (!c) { free(mm); goto oom; }
                memcpy(c, o->b + mm[k].start, ml);
                unsigned hits = 0, applied = 0; char chg[4096] = ""; const detail_param *peers[DETAIL_MAX_PARAMS]; int np = 0;
                for (int i = 0; i < p->npar; i++) if (detail_selected(&p->par[i], mask, copy) && detail_row_names(&p->par[i], o->path, mm[k].name)) peers[np++] = &p->par[i];
                q->files++;
                if (!detail_byml_edit(c, ml, q, peers, np, &o->done, (uint64_t)mm[k].start << 34, &hits, &applied, why, sizeof why, chg, sizeof chg)) {
                    detail_say(detail_rec_log, &rec, "[RED] [detail] %s member %s: %s (row \"%s\"): the member stays the game's own", o->path, mm[k].name, why, q->text); red++; *why = 0;
                    free(c); q->found++;
                    // the member as the game has it: its earlier rows' edits undone, and not counted
                    if (o->orig) memcpy(o->b + mm[k].start, o->orig + mm[k].start, ml);
                    for (int i = 0; i < o->nmlog; i++) if (o->mlog[i].start == mm[k].start) { o->rowvals[o->mlog[i].row] -= o->mlog[i].applied; o->rowfiles[o->mlog[i].row]--; o->edits -= o->mlog[i].applied; o->mlog[i].applied = 0; o->mlog[i].start = UINT32_MAX; }
                    int kept = 0; for (int i = 0; i < o->done.n; i++) if (o->done.v[i].id >> 34 != mm[k].start) o->done.v[kept++] = o->done.v[i];
                    o->done.n = kept;
                    uint32_t *nb = realloc(o->badm, (size_t)(o->nbad + 1) * sizeof *nb); if (nb) { o->badm = nb; o->badm[o->nbad++] = mm[k].start; }
                    char n2[400]; snprintf(n2, sizeof n2, "%s: left as the game has it", mm[k].name); detail_note(o, n2);
                    continue;
                }
                if (hits) {
                    q->found++; o->rowfiles[jobs[j].row]++; o->rowvals[jobs[j].row] += applied; o->edits += applied; memcpy(o->b + mm[k].start, c, ml);
                    void *nl = realloc(o->mlog, (size_t)(o->nmlog + 1) * sizeof *o->mlog);
                    if (!nl) { free(c); free(mm); goto oom; }
                    o->mlog = nl; o->mlog[o->nmlog].start = mm[k].start; o->mlog[o->nmlog].row = jobs[j].row; o->mlog[o->nmlog].applied = applied; o->nmlog++;
                    char n2[4600]; snprintf(n2, sizeof n2, "%s: %s", mm[k].name, chg); detail_note(o, n2);
                }
                free(c);
            }
            if (!matched) { detail_say(detail_rec_log, &rec, "[RED] [detail] row \"%s\": no member of %s matches %s; the row does nothing", q->text, o->path, q->member); red++; }
            free(mm);
        }
    }
    for (int i = 0; i < p->npar; i++) {
        detail_param *q = &p->par[i];
        if (detail_selected(q, mask, copy) && q->files && !q->found) { detail_say(detail_rec_log, &rec, "[RED] [detail] row \"%s\": none of its %u files has the key %s; the row does nothing", q->text, q->files, q->key); red++; }
    }
    // 4. Written into the temporary folder, then renamed
    int nfiles = 0, nloose = 0, npk = 0;
    for (int k = 0; k < nout; k++) {
        detail_out *o = &outs[k]; if (o->bad || !o->b || !o->edits) continue;
        detail_src *s = NULL; for (int i = 0; i < src.n; i++) if (!strcmp(src.v[i].path, o->path)) s = &src.v[i];
        if (!o->pack) {
            if (!s || s->n != o->n) { detail_say(detail_rec_log, &rec, "[RED] [detail] %s: its size would change (%zu -> %zu bytes): the file stays the game's own", o->path, s ? s->n : 0, o->n); red++; continue; }
            if (detail_write(tmpfs, o->path, o->b, o->n)) { detail_say(detail_rec_log, &rec, "[RED] [detail] cannot write %s/%s (errno %d): the game starts as the game draws it (Full)", tmpfs, o->path, errno); goto fail; }
            nloose++;
        } else {
            uint8_t *w; size_t wn2 = 0;
            if (!o->zh.hsize) { w = malloc(o->n); if (!w) goto oom; memcpy(w, o->b, o->n); wn2 = o->n; }   // a pack stored without compression stays so
            else if (!(w = detail_zstd_raw(&o->zh, o->b, o->n, dictid_mode, &wn2))) { detail_say(detail_rec_log, &rec, "[RED] [detail] %s: its size would change (the frame says %llu bytes, the SARC has %zu): the pack stays the game's own", o->path, (unsigned long long)o->zh.fcs, o->n); red++; continue; }
            if (o->zh.hsize) {   // decoded back as the game's loader would, with the dictionary its ID names (raw blocks read none of it): it must be the edited SARC
                uint8_t *back = io->zdecode(io->ctx, w, wn2, o->dict, o->dn, o->n, why, sizeof why);
                int same = back && !memcmp(back, o->b, o->n); free(back);
                if (!same) { detail_say(detail_rec_log, &rec, "[RED] [detail] %s: the rewritten pack does not decode back to the edited SARC (%s): the pack stays the game's own", o->path, back ? "different bytes" : why); red++; *why = 0; free(w); continue; }
            }
            int r = detail_write(tmpfs, o->path, w, wn2); free(w);
            if (r) { detail_say(detail_rec_log, &rec, "[RED] [detail] cannot write %s/%s (errno %d): the game starts as the game draws it (Full)", tmpfs, o->path, errno); goto fail; }
            npk++;
        }
        nfiles++;
        for (int i = 0; i < p->npar; i++) { p->par[i].hit_files += o->rowfiles[i]; p->par[i].values += o->rowvals[i]; }
        if (report) fprintf(report, "%s%s (%zu bytes%s)\n  %s\n", o->path, o->pack ? " [pack]" : "", o->n, o->pack ? " decoded" : "", o->chg ? o->chg : "");
    }
    {
        char line[6144]; size_t l = (size_t)snprintf(line, sizeof line, "%d files (%d loose, %d packs)%s; rows:", nfiles, nloose, npk, red ? "; red lines above" : "");
        for (int i = 0; i < p->npar && l < sizeof line - 1; i++) {
            detail_param *q = &p->par[i]; if (!detail_selected(q, mask, copy)) continue;
            const char *base = strrchr(q->pack ? q->member : q->path, '/');
            l += (size_t)snprintf(line + l, sizeof line - l, " [%s%s%s %s %s: %u values in %u of %u]", q->pack ? q->path : "", q->pack ? ":" : "", base ? base + 1 : q->pack ? q->member : q->path,
                                  q->key, strrchr(q->text, ' ') + 1, q->values, q->hit_files, q->files);
        }
        if (!nfiles) { detail_say(detail_rec_log, &rec, "[RED] [detail] %s: no file was edited (%s): no overlay, the game starts as the game draws it (Full)", name, line); detail_rm_tree(tmp); goto clean; }
        char mf[1400]; snprintf(mf, sizeof mf, "%s/detail.txt", tmp);
        FILE *m = fopen(mf, "w");
        if (!m || fprintf(m, "%s\n%s", line, rec.buf ? rec.buf : "") < 0 || fclose(m)) { if (m) fclose(m); detail_say(detail_rec_log, &rec, "[RED] [detail] cannot write %s (errno %d): the game starts as the game draws it (Full)", mf, errno); goto fail; }
        if (rename(tmp, final)) { detail_say(detail_rec_log, &rec, "[RED] [detail] cannot rename %s to %s (errno %d): the game starts as the game draws it (Full)", tmp, final, errno); goto fail; }
        detail_say(detail_rec_log, &rec, "[detail] %s: overlay %s/romfs built: %s", name, final, line);
        snprintf(outdir, on, "%s/romfs", final); ok = 1;
    }
older:   // 5. Older overlays of this game (other levels, rows or game files) go, also when this start uses an earlier one
    {
        DIR *d = opendir(temp); struct dirent *e; const char *base = strrchr(final, '/') + 1;
        while (d && (e = readdir(d))) {
            if (strncmp(e->d_name, "detail-", 7) || !strcmp(e->d_name, base)) continue;
            char c[1400]; snprintf(c, sizeof c, "%s/%s", temp, e->d_name);
            if (detail_rm_tree(c)) detail_say(detail_rec_log, &rec, "[RED] [detail] cannot remove the older overlay %s (errno %d)", c, errno);
        }
        if (d) closedir(d);
    }
    goto clean;
oom:
    detail_say(detail_rec_log, &rec, "[RED] [detail] %s: out of memory: the game starts as the game draws it (Full)", name);
fail:
    if (*tmp) detail_rm_tree(tmp);
    ok = 0; *outdir = 0;
clean:
    for (int k = 0; k < nout; k++) { free(outs[k].b); free(outs[k].chg); free(outs[k].dict); free(outs[k].done.v); free(outs[k].orig); free(outs[k].badm); free(outs[k].mlog); free(outs[k].rowvals); free(outs[k].rowfiles); }
    free(outs); free(jobs); detail_srcs_free(&src); free(rec.buf);
    if (lockfd >= 0) close(lockfd);   // (the lock goes with it)
    return ok;
}
