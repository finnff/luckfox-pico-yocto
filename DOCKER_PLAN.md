# Container support on the Luckfox Pico Ultra W — feasibility and plan

Status: **feasibility check done, nothing built or flashed yet.** Written 2026-11-15
against this repo at `4378d0e` (release v1.1.0).

Goal: replace the stock Ubuntu 22.04 image on a Luckfox Pico Ultra W with the Yocto
image from this repo, and run OCI containers ("dockers") on it.

## Verdict

- **Yocto on this board: feasible.** This layer targets exactly this board.
- **Containers: feasible, but only after a kernel rebuild.** Neither the stock Ubuntu
  kernel nor this layer's kernel as shipped can run a container.
- **Podman + crun is a better fit than Docker** for ~200 MB of RAM.

Every statement below is marked as either *verified* (I ran it) or *not verified*
(from documentation or general knowledge). Section 8 lists the open items in one place.

## 1. The board as surveyed

Read-only survey over SSH of the board running the stock Luckfox Ubuntu image.

| Item | Value |
|---|---|
| Model | Luckfox Pico Ultra W (`rockchip,rv1106g3`) |
| CPU | 1x Cortex-A7, ARMv7 with NEON/VFPv4, 32-bit only |
| RAM | 256 MB in-package; kernel reports `MemTotal` 205,904 kB (~201 MiB) |
| Storage | 8 GB eMMC (`AT2S38`), 7,577,600 KiB; no SD slot |
| Kernel | vendor 5.10.160, built 2025-05-27 |
| WiFi/BT | AIC8800DC, loaded as out-of-tree modules from `/oem/usr/ko` |
| Stock partitions | env, idblock, uboot, boot 32M, oem 512M, userdata 256M, rootfs 6G |
| Stock rootfs | 4.7 GB used of 5.9 GB (84%) |
| Swap | 1 GB swap *file* on eMMC; no zram in the kernel |
| CMA | `rk_dma_heap_cma=36M` on the stock cmdline, `CmaTotal` 47 MB |

Note: this repo's `README.md` says "128MB DDR3L". That is a mistake.
`conf/machine/luckfox-pico-ultra-w.conf` sets `LUCKFOX_RAM_MB = "256"`, and the surveyed
board has 256 MB.

## 2. Findings

### 2.1 The stock Ubuntu kernel cannot run containers (verified)

Checked through `/proc/self/ns`, `/sys/fs/cgroup/cgroup.controllers`,
`/proc/filesystems` and `/proc/kallsyms`:

- Namespaces present: `mnt` and `cgroup` only. No PID, network, IPC, UTS or user
  namespaces.
- cgroup v2 is mounted but has **zero controllers** (no memory, pids, cpu, devices).
- No overlayfs, veth, bridge, netfilter/NAT/conntrack, nftables, seccomp, BPF syscall,
  FUSE or zram.

So installing `docker.io` or `podman` on the existing Ubuntu would not work. The blocker
is the kernel, not the distribution.

### 2.2 This layer's kernel as shipped cannot either (verified)

`rv1106-emmc.cfg` enables `CGROUPS`, `CGROUP_SCHED`, `CGROUP_FREEZER`, `CGROUP_DEVICE`
and `MEMCG` for systemd. I resolved the full config the way the recipe does
(`rv1106_defconfig` + the three fragments + `olddefconfig`) and it still lacks:

- all namespaces (`NAMESPACES`, `PID_NS`, `NET_NS`, `IPC_NS`, `UTS_NS`, `USER_NS`)
- `OVERLAY_FS`
- `NET_CORE`, `VETH`, `BRIDGE`, `TUN`
- all of netfilter (`NETFILTER`, `NF_CONNTRACK`, `NF_NAT`, `NF_TABLES`, iptables)
- `CGROUP_PIDS`, `CGROUP_CPUACCT`, `BLK_CGROUP`, `CFS_BANDWIDTH`
- `CGROUP_BPF` (requested by the fragment, but dropped because `BPF_SYSCALL` is off)
- `SECCOMP`, `POSIX_MQUEUE`, `IPV6`, `SWAP`, `ZRAM`

### 2.3 The vendor kernel accepts all the missing options (verified, compile only)

Against the pinned kernel source (`LuckfoxTECH/luckfox-pico` at `824b817`,
`sysdrv/source/kernel`):

1. The fragment in section 6 was merged on top of the layer's fragments. **Every option
   in it survives `olddefconfig`.**
2. Merging it in the recipe's own order (the recipe globs `${WORKDIR}/*.cfg`, so
   `containers.cfg` is merged first) gives an identical `.config`.
3. `make zImage` succeeds. Result: `zImage` 3,157,944 bytes (XZ-compressed);
   `vmlinux` text 4.71 MB, data 2.04 MB. overlayfs, veth, bridge, zram, memcg, seccomp
   and the namespace objects are built in.

Limits of this test:

- It is a **compile test, not a boot test.** Nothing was flashed.
- It used the host's `arm-linux-gnu-gcc` 16.2.1, not the Yocto toolchain (GCC 13.x in
  scarthgap).
- The vendor tree wraps the compiler in `scripts/gcc-wrapper.py`, which turns some
  warnings into errors. GCC 16 trips it with `-Wdangling-pointer` in
  `include/linux/list.h`. I bypassed the wrapper with `CC=arm-linux-gnu-gcc`. Whether
  the Yocto build needs any such workaround with the new options is not verified; the
  recipe already builds this tree with `KCFLAGS='-Wno-error'`.
- Options that turned out not to apply: `CPUSETS` needs `SMP` (single core, not needed),
  and `NFT_CHAIN_NAT` does not exist in 5.10 (folded into `NFT_NAT`).

## 3. Constraints

**RAM.** About 200 MB is usable under the stock kernel. This layer additionally reserves
17 MB for OP-TEE (`0x03d00000 + 0x01100000`, `no-map`) and sets `rk_dma_heap_cma=66M`
for the display. `MemTotal` under the Yocto image is not measured yet.

**Docker's daemons.** `dockerd` plus `containerd` typically idle at roughly 60–100 MB
on 32-bit ARM. This is from general experience, not measured on this board. Podman has
no daemon; with `crun` the per-container overhead is a small `conmon` process. Podman
runs the same OCI images and accepts the same CLI syntax.

**Images.** Only `linux/arm/v7` images run. 32-bit ARM support is shrinking: Docker
Engine v28 is the last major version with Raspberry Pi OS armhf packages. That does not
affect a Yocto build from source, but it is a signal for image availability. Check that
every image you need publishes an `arm/v7` variant.

**CPU.** One Cortex-A7 core. Image pulls (gzip + sha256 + unpack) will be slow.

**Flashing wipes the eMMC.** The WIC is written from sector 0 and replaces the
partition table, so the Ubuntu rootfs and everything on it is gone. There is no SD slot;
flashing is over USB in maskrom mode with `rkdeveloptool` (see `README.md`). Maskrom
lives in ROM, so a bad image is recoverable by flashing again.

**WiFi after flashing.** The prebuilt release image ships no WiFi credentials. A board
that is only reachable over WiFi needs either a source build with `LUCKFOX_WIFI_SSID` /
`LUCKFOX_WIFI_PSK` / `LUCKFOX_WIFI_COUNTRY` in `local.conf`, or Ethernet, or the serial
console (UART2, 115200).

**Build host.** Scarthgap does not build natively on a very new host distribution
(the survey host is Fedora 44 with GCC 16). Build inside an Ubuntu 22.04 container.

**Security default.** `luckfox-image-minimal` sets `debug-tweaks`, which means a blank
root password. Remove it for anything left on a network.

## 4. Plan

### Phase 0 — before touching the board

- [ ] Back up what matters from the current rootfs (service configs such as the MQTT
      broker's, `/home`, anything under `/etc` that was hand-edited).
- [ ] Optionally take a full image of the eMMC over SSH for a way back to Ubuntu.
- [ ] Install `rkdeveloptool` on the flashing host and confirm the board enters maskrom
      (`rkdeveloptool ld` shows `Maskrom`).
- [ ] List the container images you intend to run and confirm each has `linux/arm/v7`.

### Phase 1 — baseline build, no containers

Purpose: prove the build environment and the board bring-up before changing anything.

- [ ] Build `luckfox-image-minimal` unmodified in an Ubuntu 22.04 container, with
      `poky` at `yocto-5.0.17` as in `README.md`, and WiFi credentials in `local.conf`.
- [ ] Flash, confirm it boots to `multi-user.target` and joins WiFi.
- [ ] Record `free -m`, `/proc/meminfo` and `systemd-analyze` as the baseline.

### Phase 2 — kernel

- [ ] Add `containers.cfg` (section 6) to
      `meta-rockchip-rv1106/recipes-kernel/linux/files/` and add
      `file://containers.cfg` to `SRC_URI` in `linux-rockchip-rv1106_5.10.bb`. The
      recipe's `do_configure` already merges every `*.cfg` in `${WORKDIR}`.
- [ ] Rebuild the kernel. The out-of-tree modules (`aic8800dc-wifi-bt`, `rknpu`) must
      be rebuilt against it; bitbake does this through the kernel dependency.
- [ ] On the board, run Docker's `check-config.sh` against `/proc/config.gz`
      (`IKCONFIG_PROC` is in the fragment) and confirm cgroup controllers appear in
      `/sys/fs/cgroup/cgroup.controllers`.
- [ ] Confirm WiFi, Bluetooth and the NPU still work. Enabling namespaces and memcg
      changes core kernel structures, so this is the step most likely to expose a
      vendor-driver problem.

### Phase 3 — userspace

Not verified: I could not run this build, and the recipe names and versions below are
from memory of meta-virtualization's scarthgap branch. Check them at build time.

- [ ] Add layers: `meta-openembedded` (`meta-oe`, `meta-python`, `meta-networking`,
      `meta-filesystems`) and `meta-virtualization`, all on `scarthgap`.
- [ ] Add `DISTRO_FEATURES:append = " virtualization seccomp"` (in
      `conf/distro/luckfox-rv1106.conf` or `local.conf`). `ipv6` is already set.
- [ ] Install the runtime. Recommended: `podman` with `crun`. Podman 5 needs `netavark`
      and `aardvark-dns`, which are Rust and add build time. Alternative: `docker-moby`.
- [ ] Add `ca-certificates`, `iptables` or `nftables`, and `kernel-modules`.
- [ ] Update `LAYERDEPENDS` / document the extra layers in `README.md`.

### Phase 4 — storage and memory

- [ ] **Rootfs size.** The image is built with `IMAGE_ROOTFS_SIZE ?= "131072"` and the
      WIC does not fill the eMMC, leaving no room for images. Either grow the root
      partition on first boot, or add a separate partition for
      `/var/lib/containers`. Growing on first boot keeps the WIC small and flashing
      fast; a fixed 6–7 GB partition in the `.wks` makes every flash write gigabytes.
- [ ] **CMA.** Reduce `rk_dma_heap_cma=66M` in `wic/extlinux-rv1106.conf` (and the
      matching bootargs in the dtsi) when running without the display. The stock image
      uses 36M. The NPU and camera also allocate from this pool, so test the lower value
      against whatever hardware stays in use.
- [ ] **zram.** Enable a zram swap device (the fragment builds in `ZRAM` with LZ4)
      instead of a swap file, to spare the eMMC.
- [ ] **Services.** Drop the demos that are not needed from `IMAGE_INSTALL`
      (`luckfox-lvgl-demo`, `rknn-demo`, `luckfox-pico-status`, `bluez5`) to save RAM.
- [ ] Remove `debug-tweaks`; ship SSH keys or a real root password.

### Phase 5 — validation

- [ ] `podman info` shows the `overlay` storage driver, cgroup v2 and `crun`.
- [ ] `podman run --rm docker.io/library/alpine uname -a` works (bridge network, NAT,
      DNS).
- [ ] Run a container with `--memory` and confirm the limit appears in its cgroup.
- [ ] Measure idle RAM with the real workload, and time a cold image pull.
- [ ] Reboot test: containers set to auto-start come back (systemd units or
      `podman generate systemd` / quadlets).

## 5. Alternatives considered

**Keep Ubuntu, replace only the kernel.** Smaller change on paper, since
`docker.io` and `podman` are in the Ubuntu 22.04 armhf archive. But the WiFi and other
drivers are out-of-tree modules in `/oem/usr/ko` built against the stock kernel config;
they all have to be rebuilt with the new kernel through the Luckfox SDK. It ends up a
comparable amount of work with a heavier userspace and an unmaintained kernel build.

**Docker instead of Podman.** Works in principle once the kernel is fixed, and
`docker-moby` is in meta-virtualization. The cost is two always-on daemons on a board
with about 200 MB. Choose it only if something needs the Docker API socket.

**The prebuilt v1.1.0 release image.** Useful to confirm maskrom flashing works, but it
has no WiFi credentials and no container support, so it is at most a smoke test.

## 6. Kernel fragment (`containers.cfg`)

Verified to resolve and compile as described in 2.3.

```
# Swap + compressed RAM swap
CONFIG_SWAP=y
CONFIG_ZSMALLOC=y
CONFIG_ZRAM=y
CONFIG_CRYPTO_LZ4=y

# Basics containers expect
CONFIG_MULTIUSER=y
CONFIG_POSIX_MQUEUE=y
CONFIG_KEYS=y
CONFIG_BPF_SYSCALL=y

# Namespaces
CONFIG_NAMESPACES=y
CONFIG_UTS_NS=y
CONFIG_IPC_NS=y
CONFIG_USER_NS=y
CONFIG_PID_NS=y
CONFIG_NET_NS=y

# cgroup controllers (cgroup v2)
CONFIG_CGROUPS=y
CONFIG_CGROUP_CPUACCT=y
CONFIG_CGROUP_PIDS=y
CONFIG_CGROUP_DEVICE=y
CONFIG_CGROUP_FREEZER=y
CONFIG_CGROUP_SCHED=y
CONFIG_FAIR_GROUP_SCHED=y
CONFIG_CFS_BANDWIDTH=y
CONFIG_MEMCG=y
CONFIG_MEMCG_SWAP=y
CONFIG_BLK_CGROUP=y
CONFIG_CGROUP_BPF=y

# seccomp
CONFIG_SECCOMP=y
CONFIG_SECCOMP_FILTER=y

# Storage
CONFIG_OVERLAY_FS=y
CONFIG_EXT4_FS_POSIX_ACL=y
CONFIG_EXT4_FS_SECURITY=y
CONFIG_TMPFS_POSIX_ACL=y
CONFIG_TMPFS_XATTR=y
CONFIG_FUSE_FS=y

# Container networking
CONFIG_IPV6=y
CONFIG_NET_CORE=y
CONFIG_TUN=y
CONFIG_VETH=y
CONFIG_BRIDGE=y
CONFIG_BRIDGE_NETFILTER=y
CONFIG_NETFILTER=y
CONFIG_NETFILTER_ADVANCED=y
CONFIG_NF_CONNTRACK=y
CONFIG_NF_NAT=y
CONFIG_NF_TABLES=y
CONFIG_NF_TABLES_INET=y
CONFIG_NFT_CT=y
CONFIG_NFT_NAT=y
CONFIG_NFT_MASQ=y
CONFIG_NFT_COMPAT=y
CONFIG_NETFILTER_XTABLES=y
CONFIG_NETFILTER_XT_MATCH_ADDRTYPE=y
CONFIG_NETFILTER_XT_MATCH_CONNTRACK=y
CONFIG_NETFILTER_XT_MATCH_COMMENT=y
CONFIG_NETFILTER_XT_MATCH_MULTIPORT=y
CONFIG_NETFILTER_XT_MARK=y
CONFIG_NETFILTER_XT_NAT=y
CONFIG_NETFILTER_XT_TARGET_MASQUERADE=y
CONFIG_IP_NF_IPTABLES=y
CONFIG_IP_NF_FILTER=y
CONFIG_IP_NF_MANGLE=y
CONFIG_IP_NF_NAT=y
CONFIG_IP_NF_TARGET_MASQUERADE=y
CONFIG_IP_NF_TARGET_REJECT=y

# Expose the running config at /proc/config.gz for check-config.sh
CONFIG_IKCONFIG=y
CONFIG_IKCONFIG_PROC=y
```

`VETH` and `TUN` depend on `NET_CORE`, which the vendor defconfig leaves off; without
that line both are silently dropped.

## 7. Reproducing the kernel check

Needs `arm-linux-gnu-gcc`, `flex`, `bison`, `bc`. About 1.4 GB of checkout and six
minutes on eight cores.

```bash
# Kernel tree only, at the SRCREV pinned by the recipe
mkdir ksrc && cd ksrc && git init -q .
git remote add origin https://github.com/LuckfoxTECH/luckfox-pico.git
git sparse-checkout init --cone && git sparse-checkout set sysdrv/source/kernel
git fetch --depth 1 --filter=blob:none origin 824b817f889c2cbff1d48fcdb18ab494a68f69d1
git checkout FETCH_HEAD && cd ..

K=$PWD/ksrc/sysdrv/source/kernel
O=$PWD/kbuild
F=$PWD/meta-rockchip-rv1106/recipes-kernel/linux/files   # path inside this repo
mk() { make -C "$K" O="$O" ARCH=arm CROSS_COMPILE=arm-linux-gnu- "$@"; }

mkdir -p "$O" && mk rv1106_defconfig
for c in containers.cfg "$F/ext4-builtin.cfg" "$F/rv1106-emmc.cfg" "$F/rv1106-optee.cfg"; do
  (cd "$O" && "$K/scripts/kconfig/merge_config.sh" -m -O "$O" "$O/.config" "$c")
done
mk olddefconfig

# Every requested option should still be set:
grep -o '^CONFIG_[A-Z0-9_]*' containers.cfg | while read -r o; do
  grep -q "^$o=[ym]" "$O/.config" || echo "MISSING $o"
done

# Compile. CC= bypasses the vendor gcc-wrapper.py, needed with a very new host GCC.
mk KCFLAGS='-Wno-error' CC=arm-linux-gnu-gcc -j"$(nproc)" zImage
```

## 8. Not verified yet

- The kernel with these options **boots** on the board and the vendor drivers
  (AIC8800DC, RKNPU, display) still work.
- The bitbake build with `meta-virtualization` completes for this machine, and the
  exact recipe names, versions and required `DISTRO_FEATURES`.
- `MemTotal` and idle RAM under the Yocto image, with and without the 66M CMA pool.
- Real RAM use of Podman or Docker on this board. The figures in section 3 are general
  estimates.
- How low `rk_dma_heap_cma` can go before the NPU or camera fail.
- The first-boot rootfs grow mechanism on this MBR layout.
- Total build time. A rough expectation is a few hours for the first build because of
  the Go and Rust toolchains.

## Sources

- This repo: `README.md`, `DEV_SETUP.md`, `meta-rockchip-rv1106/` recipes and configs
- Kernel source: <https://github.com/LuckfoxTECH/luckfox-pico> at `824b817`
- Docker on 32-bit ARM: <https://docs.docker.com/engine/install/raspberry-pi-os/> and
  <https://docs.docker.com/engine/release-notes/28/>
- Kernel option checklist: `contrib/check-config.sh` in <https://github.com/moby/moby>
