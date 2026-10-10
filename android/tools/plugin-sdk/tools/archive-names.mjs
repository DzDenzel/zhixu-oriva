/** Inspect raw central-directory names before JSZip normalizes/deduplicates them.
 * Deliberately limited to ordinary single-disk ZIP (not ZIP64), for this template.
 */
export function assertArchivePaths(input) {
  const bytes = Buffer.from(input);
  const requireValue = (condition, message) => { if (!condition) throw new Error(message); };
  let end = -1;
  for (let offset = bytes.length - 22; offset >= Math.max(0, bytes.length - 65557); offset--) {
    if (bytes.readUInt32LE(offset) === 0x06054b50 && offset + 22 + bytes.readUInt16LE(offset + 20) === bytes.length) {
      end = offset; break;
    }
  }
  requireValue(end >= 0, 'Invalid archive end record');
  const count = bytes.readUInt16LE(end + 10);
  const size = bytes.readUInt32LE(end + 12);
  let position = bytes.readUInt32LE(end + 16);
  requireValue(bytes.readUInt16LE(end + 4) === 0 && bytes.readUInt16LE(end + 6) === 0 &&
    bytes.readUInt16LE(end + 8) === count, 'Multi-disk archives are unsupported');
  requireValue(count !== 0xffff && size !== 0xffffffff && position !== 0xffffffff, 'ZIP64 archives are unsupported');
  const centralEnd = position + size;
  requireValue(centralEnd === end, 'Invalid archive central-directory bounds');
  const names = new Set();
  for (let entry = 0; entry < count; entry++) {
    requireValue(position + 46 <= centralEnd && bytes.readUInt32LE(position) === 0x02014b50, 'Invalid archive directory entry');
    const nameSize = bytes.readUInt16LE(position + 28);
    const next = position + 46 + nameSize + bytes.readUInt16LE(position + 30) + bytes.readUInt16LE(position + 32);
    requireValue(next <= centralEnd, 'Invalid archive entry bounds');
    const raw = bytes.subarray(position + 46, position + 46 + nameSize);
    const name = raw.toString('utf8');
    const parts = name.split('/');
    if (parts.at(-1) === '') parts.pop(); // A directory's trailing slash is permitted.
    requireValue(raw.equals(Buffer.from(name, 'utf8')) && name.length > 0 && !name.startsWith('/') &&
      !name.includes('\\') && !name.includes(':') && !name.includes('\0') &&
      parts.length > 0 && parts.every(part => part !== '' && part !== '.' && part !== '..'), `unsafe archive path: ${name}`);
    const canonical = parts.join('/');
    requireValue(!names.has(canonical), `duplicate archive path: ${name}`);
    names.add(canonical);
    position = next;
  }
  requireValue(position === centralEnd, 'Invalid archive directory size/count');
}
