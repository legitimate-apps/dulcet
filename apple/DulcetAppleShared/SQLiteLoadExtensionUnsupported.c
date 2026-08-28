// Apple's system SQLite is built with SQLITE_OMIT_LOAD_EXTENSION, so its stub library exports
// neither sqlite3_load_extension nor sqlite3_enable_load_extension -- verified against
// MacOSX.sdk/usr/lib/libsqlite3.tbd, which lists no such symbol. SQLiter's cinterop references both
// unconditionally, so linking DulcetCore against the system library leaves exactly those two
// undefined.
//
// Do NOT "fix" this with -Wl,-U. That was tried: it links, CI goes green, and the app then dies at
// launch with
//     dyld: symbol not found in flat namespace '_sqlite3_enable_load_extension'
// because the cinterop archive resolves in the flat namespace at load time rather than lazily. The
// Apple job builds the shell without launching it, so a binary that cannot start would have passed.
//
// Defining the symbols is the honest fix: Dulcet never loads a SQLite extension, and if some future
// code path tries, it gets a clean SQLITE_ERROR instead of a link failure or a crash on launch.
// Bundling a second SQLite purely to obtain two functions we do not use would be the larger change,
// not the safer one.

#define DULCET_SQLITE_ERROR 1

// default visibility + -export_dynamic on the app targets: the Kotlin framework resolves these in
// the FLAT namespace from a separate image, so a definition that is merely present in the app is not
// enough -- it has to be exported, or dyld aborts at load with
// "symbol not found in flat namespace". That is how this failed the second time, after the
// composition root started actually opening the database.
__attribute__((visibility("default")))
int sqlite3_enable_load_extension(void *db, int onoff);
__attribute__((visibility("default")))
int sqlite3_load_extension(void *db, const char *file, const char *proc, char **errmsg);

__attribute__((visibility("default")))
int sqlite3_enable_load_extension(void *db, int onoff) {
    (void)db;
    (void)onoff;
    return DULCET_SQLITE_ERROR;
}

__attribute__((visibility("default")))
int sqlite3_load_extension(void *db, const char *file, const char *proc, char **errmsg) {
    (void)db;
    (void)file;
    (void)proc;
    if (errmsg != 0) {
        *errmsg = 0;
    }
    return DULCET_SQLITE_ERROR;
}
