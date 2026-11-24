/* nfs_soundtuner.h - CarSoundData ini overrides for the engine sound tables
 * (see soundtuner.c). */
#ifndef NFS_SOUNDTUNER_H
#define NFS_SOUNDTUNER_H
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif

/* Read <game_root>/scripts/NFSU2CarSoundTunerSettings.ini; the CarSoundData
 * folder is read from <game_root>/scripts/ too. Call once at startup. */
void nfs_soundtuner_init(const char* game_root);
/* Guest allocator, used for replacement bank/file names (they are char* in
 * the tables). Without it only the numeric fields are overridden. */
void nfs_soundtuner_set_allocator(uint32_t (*alloc)(uint32_t bytes));
/* Copy <game_root>/CarSoundData/<table>/NN.ini files into the guest tables. Needs
 * the image mapped; idempotent, and also run on the first hook hit. */
void nfs_soundtuner_apply(void);
/* Called from recomp_hook (widescreen.c): returns 1 if `va` was ours. */
int nfs_soundtuner_hook(uint32_t va);

#ifdef __cplusplus
}
#endif
#endif