const { spawnSync } = require('child_process');

// On Windows, executables like `tsc` and `jest` are `.cmd` batch files and cannot be
// spawned directly — they require shell: true to resolve. On Unix, shell: true is
// unnecessary.
//
// With shell: true the args are re-joined into a command line, so any arg holding a
// space (an install path like `C:\CH TECH\...`) would be split into two. Quote those
// ourselves — without this, `npm install` of this package fails for every consumer
// whose checkout lives under a path with a space.
function quoteForShell(arg) {
  const value = String(arg);
  return /[\s"]/.test(value) ? `"${value.replace(/"/g, '\\"')}"` : value;
}

function spawnSyncWithAutoShell(command, args, options) {
  const useShell = process.platform === 'win32';
  const finalArgs = useShell ? (args ?? []).map(quoteForShell) : args;
  return spawnSync(command, finalArgs, { ...options, shell: useShell });
}

module.exports = { spawnSyncWithAutoShell };
