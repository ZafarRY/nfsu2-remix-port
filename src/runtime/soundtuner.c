/*
 * soundtuner.c - CarSoundData ini overrides for the engine sound tables.
 *
 * A port of NFSU2CarSoundTuner (.asi). Files are read from <game_root>/scripts/,
 * exactly where the .asi keeps them (CarSoundData/ and the Settings .ini). The .asi copies the sound tables out of
 * SPEED2.EXE, fills them from CarSoundData/<table>/NN.ini and repoints the
 * code's table operands (and the car-type switch at 0x459240) to its copies.
 * The game here is lifted C and its operands are baked-in constants, so
 * instead the tables are rewritten *in place* in guest memory, and the one
 * switch is overridden by a host hook:
 *   - table layouts (stride, field order) were read from the .asi's loader and
 *     verified against the bytes of the pinned SPEED2.EXE (4,800,512 bytes);
 *   - CarTypeMapping: hook before 0x4593C7, right after `call 0x459240`
 *     (car type -> CarDataMapping row); EAX is replaced by the .ini value.
 *
 * Every table is an array of DWORDs, one per .ini key, in the same order.
 * String keys hold a guest char*, so new text goes in a guest allocation
 * (nfs_soundtuner_set_allocator); string pointers are never written in place
 * because the exe shares them between rows.
 */
#include "recomp_types.h"
#include "nfs_soundtuner.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>

#if defined(NFSU2_EXE_F4BB0D99)
/* Other build, other addresses (not located): the module stays inert. */
void nfs_soundtuner_init(const char* r) { (void)r; }
void nfs_soundtuner_set_allocator(uint32_t (*a)(uint32_t)) { (void)a; }
void nfs_soundtuner_apply(void) {}
int nfs_soundtuner_hook(uint32_t va) { (void)va; return 0; }
#else

enum { INT_, FLT_, STR_ };
typedef struct { const char* key; int kind; } field;
typedef struct {
    const char* dir;      /* CarSoundData/<dir>/NN.ini */
    const char* section;  /* [section] inside the .ini */
    uint32_t table;       /* guest VA of row 0 */
    uint32_t size_va;     /* guest VA of the table's byte size (rows = size/stride) */
    uint32_t stride;      /* bytes per row = 4 * field count */
    const field* f;
} table_def;

static const field F_ENGINE[19] = {
    {"MainRAMBankName", STR_}, {"AuxRAMBankName", STR_}, {"CarID", INT_}, {"GinsuAccel", STR_},
    {"UseDualGinsu", INT_}, {"GinsuDecelID", INT_}, {"AccelFromIdleID", INT_},
    {"MaxRPM", FLT_}, {"MinRPM", FLT_}, {"AEMSVol", INT_}, {"GinsuAccelVol", INT_},
    {"AccelDeltaRPMThreshold", FLT_}, {"AEMSMixLRPM", FLT_}, {"GinsuMixLRPM", FLT_},
    {"AEMSMixSRPM", FLT_}, {"GinsuMixSRPM", FLT_}, {"UnkFloat1", FLT_}, {"UnkFloat2", FLT_}, {"Unk2", INT_} };
static const field F_GINSU[13] = {
    {"GinsuDecelFileName", STR_}, {"AEMSDecelVol", INT_}, {"GinsuDecelVol", INT_},
    {"MinRPM", INT_}, {"MaxRPM", INT_}, {"FadeIn", FLT_}, {"FadeOut", FLT_},
    {"DecelDeltaRPMThreshold", FLT_}, {"Unk0", INT_}, {"DecelAEMSMixLRPM", FLT_},
    {"DecelGinsuMixLRPM", FLT_}, {"DecelAEMSMixSRPM", FLT_}, {"DecelGinsuMixSRPM", FLT_} };
static const field F_SHIFT[24] = {
    {"BankName", STR_}, {"Unk1", INT_}, {"UnkFloat1", FLT_}, {"Unk2", INT_}, {"Unk3", INT_},
    {"Unk4", INT_}, {"Unk5", INT_}, {"Unk6", INT_}, {"UnkFloat2", FLT_}, {"Unk7", INT_},
    {"Unk8", INT_}, {"Unk9", INT_}, {"Unk10", INT_}, {"Unk11", INT_}, {"Unk12", INT_},
    {"Unk13", INT_}, {"UnkFloat3", FLT_}, {"Unk14", INT_}, {"Unk15", INT_}, {"Unk16", INT_},
    {"Unk17", INT_}, {"Unk18", INT_}, {"Unk19", INT_}, {"UnkFloat4", FLT_} };
static const field F_SWEET[3] = { {"SweetBank", STR_}, {"SputterVol", INT_}, {"ShiftSweetsVol", INT_} };
static const field F_ACCEL[5] = { {"UnkFloat1", FLT_}, {"Unk1", INT_}, {"Unk2", INT_}, {"Unk3", INT_}, {"Unk4", INT_} };
static const field F_TURBO[6] = {
    {"TurboBank", STR_}, {"TurboChargeVol", INT_}, {"LowBoostBovVol", INT_},
    {"HighBoostBovVol", INT_}, {"UnkFloat1", FLT_}, {"ChargeTime", FLT_} };

enum { T_ENGINE, T_GINSU, T_SHIFT, T_SWEET, T_ACCEL, T_TURBO, T_COUNT };
static const table_def k_tables[T_COUNT] = {
    { "EngineData",      "EngineData",  0x007F0DE0u, 0x007ED8F8u, 76, F_ENGINE },
    { "DualGinsu",       "DualGinsu",   0x007F1AA8u, 0x007ED8FCu, 52, F_GINSU  },
    { "ShiftPatterns",   "ShiftPattern",0x007F2860u, 0x007ED904u, 96, F_SHIFT  },
    { "SweetnerDataSet", "Sweetner",    0x007F3040u, 0x007ED90Cu, 12, F_SWEET  },
    { "AccelFromIdle",   "AccelTrans",  0x007F3248u, 0x007ED910u, 20, F_ACCEL  },
    { "TurboDataSet",    "Turbo",       0x007F3338u, 0x007ED914u, 24, F_TURBO  },
};
/* CarDataMapping: 33 rows x 4 tiers x {EngineData, ShiftPattern, TurboDataSet}. */
#define CARDATA_TABLE 0x007F2230u
#define CARDATA_SIZE  0x007ED900u
#define CARDATA_ROW   48u
static const char* const k_tier[4] = { "Stock", "Street", "Pro", "Extreme" };
static const char* const k_tier_key[3] = { "EngineData", "ShiftPattern", "TurboDataSet" };
/* Car records: base pointer at 0x8A1CCC, 0x890 bytes each, name at +0 (the
 * game's own 0x610110). A record's car type id is at [carinfo+0x10]. */
#define CAR_RECORDS   0x008A1CCCu
#define CAR_REC_SIZE  0x890u
#define HOOK_CARTYPE  0x004593C7u

static char s_root[1024];
static int s_enabled = 1, s_dump, s_applied;
static uint32_t (*s_alloc)(uint32_t);

/* ---- guest memory helpers ------------------------------------------- */
static void wr32(uint32_t va, uint32_t v) { MEM32(va) = v; }
static void gstr(uint32_t va, char* out, unsigned n) {
    unsigned i = 0;
    while (i + 1 < n && (out[i] = (char)MEM8(va + i)) != 0) i++;
    out[i] = 0;
}
static uint32_t new_guest_str(const char* s) {
    uint32_t p; size_t n = strlen(s) + 1, i;
    if (!s_alloc || !(p = s_alloc((uint32_t)n))) return 0;
    for (i = 0; i < n; i++) MEM8(p + (uint32_t)i) = (uint8_t)s[i];
    return p;
}

/* ---- tiny .ini reader (case-insensitive, `key= value`, // comments) --- */
static char* read_file(const char* rel) {
    char path[1200]; FILE* f; long n; char* buf;
    snprintf(path, sizeof path, "%s/%s", s_root, rel);
    if (!(f = fopen(path, "rb"))) return NULL;
    fseek(f, 0, SEEK_END); n = ftell(f); fseek(f, 0, SEEK_SET);
    if (n < 0 || !(buf = (char*)malloc((size_t)n + 1))) { fclose(f); return NULL; }
    n = (long)fread(buf, 1, (size_t)n, f); buf[n] = 0; fclose(f);
    return buf;
}
static int ieq(const char* a, const char* b) {
    for (; *a && *b; a++, b++) if (tolower((unsigned char)*a) != tolower((unsigned char)*b)) return 0;
    return !*a && !*b;
}
static char* trim(char* s) {
    char* e; while (isspace((unsigned char)*s)) s++;
    e = s + strlen(s); while (e > s && isspace((unsigned char)e[-1])) *--e = 0;
    return s;
}
/* Finds [section] key in `text` (modified in place); copies the value to out. */
static int ini_get(char* text, const char* section, const char* key, char* out, size_t n) {
    char cur[64] = "", *p = text, *eol, *eq, *c;
    while (*p) {
        char* line;
        eol = strpbrk(p, "\r\n"); if (eol) *eol = 0;
        line = trim(p);
        if (line[0] == '[') {
            c = strchr(line, ']'); if (c) *c = 0;
            snprintf(cur, sizeof cur, "%s", line + 1);
        } else if (ieq(cur, section) && (eq = strchr(line, '=')) != NULL) {
            *eq = 0;
            if (ieq(trim(line), key)) {
                char* v = eq + 1; c = strstr(v, "//"); if (c) *c = 0;
                snprintf(out, n, "%s", trim(v));
                return 1;
            }
        }
        if (!eol) break;
        p = eol + 1;
        while (*p == '\r' || *p == '\n') p++;
    }
    return 0;
}
/* ini_get() edits the text, so each lookup works on a fresh copy. */
static int ini_val(const char* text, const char* sec, const char* key, char* out, size_t n) {
    char* copy = strdup(text); int r;
    if (!copy) return 0;
    r = ini_get(copy, sec, key, out, n); free(copy); return r;
}

/* ---- table overrides ------------------------------------------------- */
static uint32_t rows(uint32_t size_va, uint32_t stride) { return MEM32(size_va) / stride; }

static void apply_table(const table_def* t) {
    uint32_t n = rows(t->size_va, t->stride), i, k, nf = t->stride / 4;
    for (i = 0; i < n; i++) {
        char rel[160], val[260]; char* text;
        snprintf(rel, sizeof rel, "CarSoundData/%s/%02u.ini", t->dir, i);
        if (!(text = read_file(rel))) continue;
        for (k = 0; k < nf; k++) {
            uint32_t va = t->table + i * t->stride + k * 4;
            if (!ini_val(text, t->section, t->f[k].key, val, sizeof val)) continue;
            if (t->f[k].kind == INT_) wr32(va, (uint32_t)(int32_t)strtol(val, NULL, 0));
            else if (t->f[k].kind == FLT_) { float f = (float)strtod(val, NULL); uint32_t u; memcpy(&u, &f, 4); wr32(va, u); }
            else { uint32_t p = new_guest_str(val); if (p) wr32(va, p); }
        }
        free(text);
    }
}

static void apply_cardata(void) {
    uint32_t n = rows(CARDATA_SIZE, CARDATA_ROW), i, t, k;
    uint32_t lim[3] = { rows(k_tables[T_ENGINE].size_va, 76), rows(k_tables[T_SHIFT].size_va, 96),
                        rows(k_tables[T_TURBO].size_va, 24) };
    for (i = 0; i < n; i++) {
        char rel[160], val[64]; char* text;
        snprintf(rel, sizeof rel, "CarSoundData/CarDataMapping/%02u.ini", i);
        if (!(text = read_file(rel))) continue;
        for (t = 0; t < 4; t++) for (k = 0; k < 3; k++) {
            if (!ini_val(text, k_tier[t], k_tier_key[k], val, sizeof val)) continue;
            { uint32_t v = (uint32_t)strtoul(val, NULL, 0);
              if (v < lim[k]) wr32(CARDATA_TABLE + i * CARDATA_ROW + (t * 3 + k) * 4, v); }  /* ignore bad indexes */
        }
        free(text);
    }
}

/* DumpSoundData=1: write the exe's own tables out as .ini (folders must exist). */
static void dump_ini(const char* rel, const char* section, const field* f, uint32_t row, uint32_t nf) {
    char path[1200]; FILE* o; uint32_t k;
    snprintf(path, sizeof path, "%s/%s", s_root, rel);
    if (!(o = fopen(path, "wb"))) return;
    fprintf(o, "[%s]\r\n", section);
    for (k = 0; k < nf; k++) {
        uint32_t v = MEM32(row + k * 4);
        if (f[k].kind == STR_) { char s[260]; gstr(v, s, sizeof s); fprintf(o, "%s=%s\r\n", f[k].key, s); }
        else if (f[k].kind == FLT_) { float x; memcpy(&x, &v, 4); fprintf(o, "%s= %f\r\n", f[k].key, x); }
        else fprintf(o, "%s= %d\r\n", f[k].key, (int)v);
    }
    fclose(o);
}
static void dump_all(void) {
    uint32_t i, t, k;
    for (t = 0; t < T_COUNT; t++) {
        const table_def* d = &k_tables[t];
        for (i = 0; i < rows(d->size_va, d->stride); i++) {
            char rel[160]; snprintf(rel, sizeof rel, "CarSoundData/%s/%02u.ini", d->dir, i);
            dump_ini(rel, d->section, d->f, d->table + i * d->stride, d->stride / 4);
        }
    }
    for (i = 0; i < rows(CARDATA_SIZE, CARDATA_ROW); i++) {
        char path[1200]; FILE* o;
        snprintf(path, sizeof path, "%s/CarSoundData/CarDataMapping/%02u.ini", s_root, i);
        if (!(o = fopen(path, "wb"))) continue;
        for (t = 0; t < 4; t++) {
            fprintf(o, "[%s]\r\n", k_tier[t]);
            for (k = 0; k < 3; k++) fprintf(o, "%s= %u\r\n", k_tier_key[k], MEM32(CARDATA_TABLE + i * CARDATA_ROW + (t * 3 + k) * 4));
        }
        fclose(o);
    }
}

/* ---- public API ------------------------------------------------------ */
void nfs_soundtuner_init(const char* game_root) {
    char* ini; char v[32];
    snprintf(s_root, sizeof s_root, "%s/scripts", game_root);   /* where the .asi mods live */
    if ((ini = read_file("NFSU2CarSoundTunerSettings.ini")) != NULL) {
        if (ini_val(ini, "Main", "EnableCarSoundTuner", v, sizeof v)) s_enabled = atoi(v) != 0;
        if (ini_val(ini, "Debug", "DumpSoundData", v, sizeof v)) s_dump = atoi(v) != 0;
        free(ini);
    }
}
void nfs_soundtuner_set_allocator(uint32_t (*alloc)(uint32_t)) { s_alloc = alloc; }

void nfs_soundtuner_apply(void) {
    unsigned t;
    if (s_applied || !s_enabled) return;
    s_applied = 1;
    if (s_dump) dump_all();
    for (t = 0; t < T_COUNT; t++) apply_table(&k_tables[t]);
    apply_cardata();
}

/* Hook before `lea edi,[ecx+0x300]` in 0x4593B0, right after the game's
 * `call 0x459240` (car type -> CarDataMapping row, result in EAX, ecx = car
 * info whose [+0x10] is the car type id). CarTypeMapping/<NAME>.ini replaces it. */
int nfs_soundtuner_hook(uint32_t va) {
    char name[64], rel[160], val[32]; char* text;
    uint32_t car;
    if (va != HOOK_CARTYPE) return 0;
    if (!s_enabled) return 1;
    nfs_soundtuner_apply();
    car = MEM32(g_ecx + 0x10);
    gstr(MEM32(CAR_RECORDS) + car * CAR_REC_SIZE, name, sizeof name);
    snprintf(rel, sizeof rel, "CarSoundData/CarTypeMapping/%s.ini", name);
    if ((text = read_file(rel)) != NULL) {
        if (ini_val(text, "CarTypeMapping", "CarDataMapping", val, sizeof val)) {
            uint32_t v = (uint32_t)strtoul(val, NULL, 0);
            if (v < rows(CARDATA_SIZE, CARDATA_ROW)) g_eax = v;
        }
        free(text);
    } else if (s_dump) {                       /* DumpSoundData: save the game's own pick */
        char path[1200]; FILE* o;
        snprintf(path, sizeof path, "%s/%s", s_root, rel);
        if ((o = fopen(path, "wb")) != NULL) { fprintf(o, "[CarTypeMapping]\r\nCarDataMapping= %u\r\n", g_eax); fclose(o); }
    }
    return 1;
}
#endif /* !NFSU2_EXE_F4BB0D99 */