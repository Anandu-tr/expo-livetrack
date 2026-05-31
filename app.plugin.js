// This file configures the entry point for the expo-livetrack config plugin
// and tells Expo CLI to use the built (transpiled) plugin from `plugin/build`.
const pkg = require('./package.json');

let withLiveTrack;
try {
  withLiveTrack = require('./plugin/build').default;
} catch (e) {
  throw new Error(
    `expo-livetrack: the config plugin has not been built. Run \`npm run build plugin\` ` +
      `(or \`npm run prepare\`) before using the plugin. Original error: ${e.message}`
  );
}

// Wrap so the plugin only runs once even if listed multiple times.
const {
  createRunOncePlugin,
} = require('@expo/config-plugins');

module.exports = createRunOncePlugin(withLiveTrack, pkg.name, pkg.version);
