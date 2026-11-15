# Container host image for the Luckfox Pico Ultra W: luckfox-image-minimal
# without the display/NPU/Bluetooth demos, plus podman (crun, netavark),
# zram swap and a root filesystem that grows to fill the eMMC on first boot.
#
# Needs meta-virtualization and its dependencies; see README.md.

# No blank root password on this image. Login is by SSH key, see below.
LUCKFOX_DEBUG_TWEAKS ?= ""

require recipes-core/images/luckfox-image-minimal.bb

SUMMARY = "Luckfox RV1106 container host image (podman)"
DESCRIPTION = "Headless image for the Luckfox Pico Ultra W that runs OCI \
containers with podman, crun and netavark."

IMAGE_INSTALL:remove = " \
    bluez5 \
    luckfox-bt-init \
    luckfox-lvgl-demo \
    luckfox-pico-status \
    rknn-demo \
"

IMAGE_INSTALL:append = " \
    podman \
    crun \
    ca-certificates \
    iptables \
    luckfox-container-config \
    luckfox-growfs \
    zram \
"

# 4 KiB-block root filesystem; the CMA pool is set by LUCKFOX_CMA_SIZE (kernel recipe).
WKS_FILE = "luckfox-rv1106-emmc-containers.wks"

# Free space in the flashed image before luckfox-growfs has run (KiB).
IMAGE_ROOTFS_EXTRA_SPACE = "65536"

# Root's authorized_keys, the only way in when debug-tweaks is off. Set it in
# local.conf to a file the build can read, for example
#   LUCKFOX_SSH_AUTHORIZED_KEYS_FILE = "${TOPDIR}/conf/authorized_keys"
LUCKFOX_SSH_AUTHORIZED_KEYS_FILE ??= ""

python () {
    keys = d.getVar('LUCKFOX_SSH_AUTHORIZED_KEYS_FILE')
    if keys:
        d.appendVarFlag('do_rootfs', 'file-checksums', ' %s:True' % keys)
    elif not bb.utils.contains_any('IMAGE_FEATURES',
                                   'debug-tweaks empty-root-password', True, False, d):
        bb.fatal("luckfox-image-containers has a locked root account and no SSH "
                 "key. Set LUCKFOX_SSH_AUTHORIZED_KEYS_FILE in local.conf, or "
                 "LUCKFOX_DEBUG_TWEAKS = \"debug-tweaks\" for a blank root password.")
}

install_root_ssh_keys() {
    if [ -n "${LUCKFOX_SSH_AUTHORIZED_KEYS_FILE}" ]; then
        install -d -m 0700 ${IMAGE_ROOTFS}${ROOT_HOME}/.ssh
        install -m 0600 ${LUCKFOX_SSH_AUTHORIZED_KEYS_FILE} \
            ${IMAGE_ROOTFS}${ROOT_HOME}/.ssh/authorized_keys
    fi
}

ROOTFS_POSTPROCESS_COMMAND:append = " install_root_ssh_keys;"

# Start containers created with --restart=always again after a reboot.
enable_podman_restart() {
    install -d ${IMAGE_ROOTFS}${sysconfdir}/systemd/system/default.target.wants
    ln -sf ${systemd_system_unitdir}/podman-restart.service \
        ${IMAGE_ROOTFS}${sysconfdir}/systemd/system/default.target.wants/podman-restart.service
}

ROOTFS_POSTPROCESS_COMMAND:append = " enable_podman_restart;"
