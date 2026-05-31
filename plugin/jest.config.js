/** Jest config for the config-plugin subtarget.
 *
 * The plugin runs in plain Node (prebuild time), so we transpile TS with
 * babel-preset-expo and resolve the Babel config upward to the repo root
 * (rootMode: 'upward') because Jest sets rootDir to this `plugin/` dir.
 */
module.exports = {
  preset: 'jest-expo/node',
  rootDir: __dirname,
  roots: ['<rootDir>/src'],
  transform: {
    '\\.[jt]sx?$': ['babel-jest', { rootMode: 'upward' }],
  },
};
