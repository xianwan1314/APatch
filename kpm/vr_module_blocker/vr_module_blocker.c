/* SPDX-License-Identifier: GPL-2.0-or-later */

#include <compiler.h>
#include <kpmalloc.h>
#include <kpmodule.h>
#include <ksyms.h>
#include <kputils.h>
#include <linux/elf.h>
#include <linux/err.h>
#include <linux/fs.h>
#include <linux/printk.h>
#include <linux/string.h>
#include <syscall.h>
#include <uapi/asm-generic/unistd.h>

KPM_NAME("apatch-vr-module-blocker");
KPM_VERSION("1.0.0");
KPM_LICENSE("GPL v2");
KPM_AUTHOR("APatch");
KPM_DESCRIPTION("Block vr.ko from init_module/finit_module before kernel init");

#define VR_MAX_SECTIONS 512
#define VR_MAX_SHSTRTAB (64 * 1024)
#define VR_MAX_MODINFO (16 * 1024)

#ifndef SEEK_SET
#define SEEK_SET 0
#endif

#ifndef SEEK_END
#define SEEK_END 2
#endif

extern struct file *kfunc_def(fget)(int fd);
extern struct file *kfunc_def(fput)(struct file *file);

struct vr_module_reader {
    const char __user *umod;
    unsigned long umod_len;
    struct file *file;
    loff_t file_size;
};

static unsigned long (*vr_copy_from_user)(void *to, const void __user *from, unsigned long n);

static void vr_resolve_copy_from_user(void)
{
    const char *names[] = {
        "copy_from_user",
        "__copy_from_user",
        "_copy_from_user",
        "raw_copy_from_user",
    };
    int i;

    for (i = 0; i < (int)(sizeof(names) / sizeof(names[0])); i++) {
        vr_copy_from_user = (typeof(vr_copy_from_user))kallsyms_lookup_name(names[i]);
        if (vr_copy_from_user)
            return;
    }
}

static int vr_reader_read(struct vr_module_reader *reader, loff_t offset, void *buf, size_t len)
{
    loff_t total = reader->umod ? (loff_t)reader->umod_len : reader->file_size;

    if (len == 0)
        return -1;
    if (offset < 0 || (loff_t)len > total || offset > total - (loff_t)len)
        return -1;

    if (reader->umod) {
        if (!vr_copy_from_user)
            return -1;
        if (vr_copy_from_user(buf, reader->umod + offset, len) != 0)
            return -1;
        return 0;
    }

    {
        loff_t pos = offset;
        ssize_t n = kernel_read(reader->file, buf, len, &pos);
        if (n < 0 || (size_t)n != len)
            return -1;
    }

    return 0;
}

static bool vr_module_is_target(struct vr_module_reader *reader)
{
    Elf64_Ehdr ehdr;
    Elf64_Shdr *shdrs = NULL;
    char *shstrtab = NULL;
    char *modinfo = NULL;
    Elf64_Shdr *shstr_sh;
    Elf64_Shdr *modinfo_sh = NULL;
    unsigned int shnum;
    unsigned int i;
    unsigned long shtab_bytes;
    bool is_vr = false;

    if (vr_reader_read(reader, 0, &ehdr, sizeof(ehdr)))
        return false;

    if (memcmp(ehdr.e_ident, ELFMAG, SELFMAG) != 0)
        return false;
    if (ehdr.e_ident[EI_CLASS] != ELFCLASS64)
        return false;
    if (ehdr.e_type != ET_REL)
        return false;
    if (ehdr.e_shentsize != sizeof(Elf64_Shdr))
        return false;

    shnum = ehdr.e_shnum;
    if (shnum == 0 || shnum > VR_MAX_SECTIONS)
        return false;
    if (ehdr.e_shstrndx >= shnum)
        return false;

    shtab_bytes = (unsigned long)shnum * sizeof(Elf64_Shdr);
    shdrs = kp_malloc(shtab_bytes);
    if (!shdrs)
        return false;
    if (vr_reader_read(reader, ehdr.e_shoff, shdrs, shtab_bytes))
        goto out;

    shstr_sh = &shdrs[ehdr.e_shstrndx];
    if (shstr_sh->sh_size == 0 || shstr_sh->sh_size > VR_MAX_SHSTRTAB)
        goto out;

    shstrtab = kp_malloc(shstr_sh->sh_size);
    if (!shstrtab)
        goto out;
    if (vr_reader_read(reader, shstr_sh->sh_offset, shstrtab, shstr_sh->sh_size))
        goto out;

    for (i = 0; i < shnum; i++) {
        Elf64_Shdr *sh = &shdrs[i];
        const char *name;
        unsigned long remaining;

        if (sh->sh_name >= shstr_sh->sh_size)
            continue;

        name = shstrtab + sh->sh_name;
        remaining = shstr_sh->sh_size - sh->sh_name;
        if (strnlen(name, remaining) >= remaining)
            continue;
        if (strcmp(name, ".modinfo") == 0) {
            modinfo_sh = sh;
            break;
        }
    }

    if (!modinfo_sh)
        goto out;
    if (modinfo_sh->sh_size == 0 || modinfo_sh->sh_size > VR_MAX_MODINFO)
        goto out;

    modinfo = kp_malloc(modinfo_sh->sh_size);
    if (!modinfo)
        goto out;
    if (vr_reader_read(reader, modinfo_sh->sh_offset, modinfo, modinfo_sh->sh_size))
        goto out;

    {
        const char *p = modinfo;
        const char *end = modinfo + modinfo_sh->sh_size;

        while (p < end) {
            const char *nul = memchr(p, '\0', end - p);
            size_t entlen;

            if (!nul)
                break;

            entlen = nul - p;
            if (entlen > 5 && memcmp(p, "name=", 5) == 0) {
                const char *value = p + 5;
                size_t vlen = entlen - 5;

                if (vlen == 2 && value[0] == 'v' && value[1] == 'r')
                    is_vr = true;
                break;
            }
            p = nul + 1;
        }
    }

out:
    kp_free(modinfo);
    kp_free(shstrtab);
    kp_free(shdrs);
    return is_vr;
}

static void before_init_module(hook_fargs3_t *args, void *udata)
{
    struct vr_module_reader reader = {
        .umod = (const char __user *)syscall_argn(args, 0),
        .umod_len = (unsigned long)syscall_argn(args, 1),
        .file = NULL,
        .file_size = 0,
    };

    if (!reader.umod || reader.umod_len < sizeof(Elf64_Ehdr))
        return;

    if (!vr_copy_from_user)
        return;

    if (!vr_module_is_target(&reader))
        return;

    args->skip_origin = 1;
    args->ret = 0;
    pr_info("apatch vr blocker: blocked vr (init_module)\n");
}

static void before_finit_module(hook_fargs3_t *args, void *udata)
{
    int fd = (int)syscall_argn(args, 0);
    struct file *file;
    struct vr_module_reader reader;
    loff_t file_size;

    if (!kfunc(fget) || !kfunc(fput) || !kfunc(vfs_llseek))
        return;

    file = kfunc(fget)(fd);
    if (!file)
        return;

    file_size = vfs_llseek(file, 0, SEEK_END);
    vfs_llseek(file, 0, SEEK_SET);
    if (file_size < (loff_t)sizeof(Elf64_Ehdr)) {
        kfunc(fput)(file);
        return;
    }

    reader.umod = NULL;
    reader.umod_len = 0;
    reader.file = file;
    reader.file_size = file_size;

    if (vr_module_is_target(&reader)) {
        args->skip_origin = 1;
        args->ret = 0;
        pr_info("apatch vr blocker: blocked vr (finit_module)\n");
    }

    kfunc(fput)(file);
}

static long vr_module_blocker_init(const char *args, const char *event, void *reserved)
{
    hook_err_t err;

    vr_resolve_copy_from_user();

    err = hook_syscalln(__NR_init_module, 3, before_init_module, NULL, NULL);
    if (err) {
        pr_err("apatch vr blocker: hook init_module failed: %d\n", err);
        return 0;
    }

    err = hook_syscalln(__NR_finit_module, 3, before_finit_module, NULL, NULL);
    if (err) {
        pr_err("apatch vr blocker: hook finit_module failed: %d\n", err);
        unhook_syscalln(__NR_init_module, before_init_module, NULL);
        return 0;
    }

    pr_info("apatch vr blocker: hooks installed, event=%s\n", event ? event : "unknown");
    return 0;
}

static long vr_module_blocker_exit(void *reserved)
{
    unhook_syscalln(__NR_init_module, before_init_module, NULL);
    unhook_syscalln(__NR_finit_module, before_finit_module, NULL);
    pr_info("apatch vr blocker: hooks removed\n");
    return 0;
}

KPM_INIT(vr_module_blocker_init);
KPM_EXIT(vr_module_blocker_exit);
