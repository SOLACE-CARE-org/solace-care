// Jest for the app (jest-expo). Packages that ship untranspiled ESM are transformed.
module.exports = {
  preset: 'jest-expo',
  setupFiles: ['./jest.setup.ts'],
  moduleNameMapper: {
    '^@/(.*)$': '<rootDir>/src/$1',
    // Its "react-native" export is an .mjs file Jest cannot load; Node's resolution picks the
    // CommonJS build, wherever pnpm placed the package.
    '^lucide-react-native$': require.resolve('lucide-react-native'),
  },
  transformIgnorePatterns: [
    'node_modules/(?!((jest-)?react-native|@react-native(-community)?|expo(nent)?|@expo(nent)?/.*|@expo-google-fonts/.*|react-navigation|@react-navigation/.*|react-native-svg|lucide-react-native|@solace/.*|standard-navigation))',
  ],
};
