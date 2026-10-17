import { loadRuntimeConfig } from './environments/runtime-config';

// The app is imported only after the runtime settings are in place, because some modules (the
// sidebar's feature flags) read them when they load.
loadRuntimeConfig()
  .then(() => Promise.all([import('@angular/platform-browser'), import('./app/app.config'), import('./app/app')]))
  .then(([{ bootstrapApplication }, { appConfig }, { App }]) => bootstrapApplication(App, appConfig))
  .catch((err) => console.error(err));
