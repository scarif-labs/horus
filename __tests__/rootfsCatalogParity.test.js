const {readFileSync} = require('node:fs');
const {join} = require('node:path');

const {DEBIAN_ROOTFS} = require('../src/terminal/distroContract');

// The JS catalog mirrors RootfsCatalog.kt by hand; catch the two drifting apart.
describe('rootfs catalog parity', () => {
  it('mirrors the native debian catalog entry exactly', () => {
    const native = readFileSync(
      join(__dirname, '../android/app/src/main/java/com/scariflabs/horus/terminal/RootfsCatalog.kt'),
      'utf8',
    );
    const debian = native.slice(native.indexOf('val DEBIAN = '));
    expect(debian).toContain(`distro = DISTRO_DEBIAN`);
    expect(debian).toContain(`rootfsId = "${DEBIAN_ROOTFS.rootfsId}"`);
    expect(debian).toContain(`release = "${DEBIAN_ROOTFS.release}"`);
    expect(debian).toContain(`url = "${DEBIAN_ROOTFS.url}"`);
    expect(debian).toContain(`sha256 = "${DEBIAN_ROOTFS.sha256}"`);
    expect(debian).toContain(`sizeBytes = ${DEBIAN_ROOTFS.sizeBytes.toLocaleString('en-US').replace(/,/g, '_')}L`);
    const markers = DEBIAN_ROOTFS.probeMarkers;
    expect(debian).toContain(`probeBegin = "${markers[0]}"`);
    expect(debian).toContain(`probeEnd = "${markers[markers.length - 1]}"`);
    // The middle markers are the probe checks' expected lines, in order.
    const expected = [...debian.matchAll(/ProbeCheck\(listOf\([^)]*\), (?:"([^"]*)"|AlpineRootfsCatalog\.EXPECTED_GUEST_ARCH)\)/g)]
      .map(match => match[1] ?? 'aarch64');
    expect(expected).toEqual(markers.slice(1, -1));
  });
});
