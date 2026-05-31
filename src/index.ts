// Reexport the native module. On web, it will be resolved to LiveTrackModule.web.ts
// and on native platforms to LiveTrackModule.ts
export { default } from './LiveTrackModule';
export * from './LiveTrack.types';
