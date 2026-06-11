// A fixed palette of visually distinct colors for overlaying multiple routes.
const PALETTE = [
  '#e6194b',
  '#3cb44b',
  '#4363d8',
  '#f58231',
  '#911eb4',
  '#42d4f4',
  '#f032e6',
  '#bfef45',
  '#469990',
  '#9a6324',
];

// Deterministic color per uid so a user keeps the same color across renders.
export function colorForUid(uid: string): string {
  let hash = 0;
  for (let i = 0; i < uid.length; i++) {
    hash = (hash * 31 + uid.charCodeAt(i)) | 0;
  }
  return PALETTE[Math.abs(hash) % PALETTE.length];
}
