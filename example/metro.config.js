const { getDefaultConfig } = require('expo/metro-config');
const path = require('path');

const config = getDefaultConfig(__dirname);

// Only use the example's own node_modules. Pulling from the parent (the
// expo-livetrack repo root) drags in an older @react-native/codegen that
// can't parse the RN 0.85+ VirtualView components, which breaks bundling.
config.resolver.nodeModulesPaths = [path.resolve(__dirname, './node_modules')];

// Block parent's react / react-native to prevent duplicate-copy resolution.
config.resolver.blockList = [
  ...Array.from(config.resolver.blockList ?? []),
  new RegExp(path.resolve('..', 'node_modules', 'react').replace(/\\/g, '\\\\')),
  new RegExp(path.resolve('..', 'node_modules', 'react-native').replace(/\\/g, '\\\\')),
];

// expo-livetrack is symlinked under example/node_modules/expo-livetrack -> ../..
// so it resolves through the normal package resolver. We do NOT need extraNodeModules.
// But we DO need watchFolders so Metro picks up edits in src/ live.
config.watchFolders = [path.resolve(__dirname, '..', 'src'), path.resolve(__dirname, '..', 'build')];

config.transformer.getTransformOptions = async () => ({
  transform: {
    experimentalImportSupport: false,
    inlineRequires: true,
  },
});

module.exports = config;
