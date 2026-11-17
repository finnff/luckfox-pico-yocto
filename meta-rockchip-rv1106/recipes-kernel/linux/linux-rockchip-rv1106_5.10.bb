SUMMARY = "Rockchip BSP kernel for RV1106"
DESCRIPTION = "Vendor BSP Linux kernel 5.10.x for the RV1106 SoC family \
(single Cortex-A7 @ 1.2GHz). Includes SoC drivers, DTS files, and \
hardware abstraction for the RV1106/RV1103 platform."
HOMEPAGE = "https://github.com/LuckfoxTECH/luckfox-pico"

LICENSE = "GPL-2.0-only"
LIC_FILES_CHKSUM = "file://COPYING;md5=6bc538ed5bd9a7fc9398086aedcd7e46"

DEPENDS += "openssl-native coreutils-native"

# Luckfox SDK kernel — has display/VOP patches needed for RGB LCD
SRCREV = "824b817f889c2cbff1d48fcdb18ab494a68f69d1"
SRC_URI = " \
    git://github.com/LuckfoxTECH/luckfox-pico.git;protocol=https;branch=main;destsuffix=git \
    file://rv1106g-luckfox-pico-ultra-w.dts \
    file://rv1106-luckfox-pico-ultra-ipc.dtsi \
    file://rv1106-luckfox-header-uart3.dtsi \
    file://ext4-builtin.cfg \
    file://rv1106-emmc.cfg \
    file://rv1106-optee.cfg \
    file://containers.cfg \
    file://logo_linux_clut224.ppm \
"

COMPATIBLE_MACHINE = "(luckfox-pico-ultra-w)"

LINUX_VERSION = "5.10.160"
LINUX_VERSION_EXTENSION = "-rockchip-rv1106"

# Suppress GCC 13 warnings not in this kernel version
# Suppress GCC 13 warnings not present in this older kernel
KERNEL_EXTRA_ARGS += "KCFLAGS='-Wno-error'"

inherit kernel

S = "${WORKDIR}/git/sysdrv/source/kernel"

KBUILD_DEFCONFIG = "rv1106_defconfig"

# Size of the Rockchip DMA-heap CMA pool (rk_dma_heap_cma= in the device tree
# bootargs). U-Boot merges the DT bootargs over the extlinux.conf append line
# and the DT value wins, so this is the place that changes it. 66M is what the
# RGB panel and NPU demos need; a headless container host can use 36M (the
# stock Luckfox value) and get the difference back as ordinary RAM.
LUCKFOX_CMA_SIZE ?= "66M"
do_configure[vardeps] += "LUCKFOX_CMA_SIZE"

# Extra .dtsi files (from SRC_URI) included at the end of the board device
# tree. "rv1106-luckfox-header-uart3.dtsi" puts UART3 on header pins 32/33
# (GPIO1_A0/A1) as /dev/ttyS3.
LUCKFOX_DTS_EXTRA ?= ""
do_configure[vardeps] += "LUCKFOX_DTS_EXTRA"

# Install out-of-tree DTS/DTSI files and apply patches
do_configure:prepend() {
    # Replace the SDK's custom Luckfox-branded boot logo with the
    # standard upstream Linux Tux. The kernel embeds the .ppm at
    # build time via scripts/pnmtologo, so overwriting the source
    # file before do_configure is enough.
    if [ -f ${WORKDIR}/logo_linux_clut224.ppm ]; then
        cp ${WORKDIR}/logo_linux_clut224.ppm \
            ${S}/drivers/video/logo/logo_linux_clut224.ppm
    fi

    # Defensive: older SDK commits of rockchip_drm_vop.c were missing
    # DRM_MODE_CONNECTOR_DPI in the output-enable switch, so the RGB output
    # gate never opened. Newer commits already fall through to LVDS; this
    # sed is a no-op there but keeps us safe on older SRCREV pins.
    if ! grep -q "DRM_MODE_CONNECTOR_DPI" ${S}/drivers/gpu/drm/rockchip/rockchip_drm_vop.c; then
        sed -i '/case DRM_MODE_CONNECTOR_LVDS:/i\\tcase DRM_MODE_CONNECTOR_DPI:' \
            ${S}/drivers/gpu/drm/rockchip/rockchip_drm_vop.c
    fi

    # Copy dtsi includes first
    for dtsi in ${WORKDIR}/*.dtsi; do
        [ -f "$dtsi" ] || continue
        cp "$dtsi" ${S}/arch/arm/boot/dts/
    done
    sed -i 's/rk_dma_heap_cma=[0-9]*[KMG]/rk_dma_heap_cma=${LUCKFOX_CMA_SIZE}/' \
        ${S}/arch/arm/boot/dts/rv1106-luckfox-pico-ultra-ipc.dtsi

    for dts in ${WORKDIR}/*.dts; do
        [ -f "$dts" ] || continue
        cp "$dts" ${S}/arch/arm/boot/dts/
        for extra in ${LUCKFOX_DTS_EXTRA}; do
            echo "#include \"$extra\"" >> ${S}/arch/arm/boot/dts/$(basename "$dts")
        done
        dtb=$(basename "$dts" .dts).dtb
        # Add to Makefile if not already present
        if ! grep -q "$dtb" ${S}/arch/arm/boot/dts/Makefile; then
            # Find an rv1106 entry to anchor our addition, or append at end
            if grep -q "rv1106" ${S}/arch/arm/boot/dts/Makefile; then
                sed -i "/rv1106.*\.dtb/a\\\\t${dtb} \\\\" \
                    ${S}/arch/arm/boot/dts/Makefile
            else
                echo "dtb-\$(CONFIG_ARCH_ROCKCHIP) += ${dtb}" >> \
                    ${S}/arch/arm/boot/dts/Makefile
            fi
        fi
    done
}

do_configure() {
    # SDK kernel already has correct display drivers
    oe_runmake -C ${S} O=${B} ${KBUILD_DEFCONFIG}

    # Merge config fragments
    for cfg in ${WORKDIR}/*.cfg; do
        [ -f "$cfg" ] || continue
        ${S}/scripts/kconfig/merge_config.sh -m -O ${B} ${B}/.config "$cfg"
    done

    oe_runmake -C ${S} O=${B} olddefconfig
}

PROVIDES += "virtual/kernel"
