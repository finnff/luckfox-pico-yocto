SUMMARY = "Grow the root partition and filesystem to fill the disk on first boot"
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

SRC_URI = "file://luckfox-growfs file://luckfox-growfs.service"
S = "${WORKDIR}"

inherit systemd allarch

do_install() {
    install -D -m 0755 ${WORKDIR}/luckfox-growfs ${D}${sbindir}/luckfox-growfs
    install -D -m 0644 ${WORKDIR}/luckfox-growfs.service \
        ${D}${systemd_system_unitdir}/luckfox-growfs.service
}

SYSTEMD_SERVICE:${PN} = "luckfox-growfs.service"

RDEPENDS:${PN} = "util-linux-sfdisk util-linux-partx util-linux-mountpoint e2fsprogs-resize2fs"
