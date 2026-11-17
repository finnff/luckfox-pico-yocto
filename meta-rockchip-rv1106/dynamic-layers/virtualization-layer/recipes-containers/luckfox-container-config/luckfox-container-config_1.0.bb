SUMMARY = "Podman configuration for low-memory Luckfox RV1106 boards"
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

SRC_URI = "file://10-luckfox.conf file://luckfox-containers.conf"
S = "${WORKDIR}"

inherit allarch

do_install() {
    install -D -m 0644 ${WORKDIR}/10-luckfox.conf \
        ${D}${sysconfdir}/containers/containers.conf.d/10-luckfox.conf
    install -D -m 0644 ${WORKDIR}/luckfox-containers.conf \
        ${D}${nonarch_libdir}/tmpfiles.d/luckfox-containers.conf
}

FILES:${PN} = "${sysconfdir}/containers ${nonarch_libdir}/tmpfiles.d"
