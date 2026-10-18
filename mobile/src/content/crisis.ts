/**
 * Emergency numbers bundled with the app, so Urgent help works with no connection at all (System
 * Design, Crisis button; D39). Cameroon: police 117, fire 118, ambulance (SAMU) 119. The Content
 * module will override them per country (epic 12); the owner confirms them before launch.
 */
export type EmergencyNumber = { number: string; service: 'ambulance' | 'police' | 'fire' };

export const EMERGENCY_NUMBERS: EmergencyNumber[] = [
  { number: '119', service: 'ambulance' },
  { number: '117', service: 'police' },
  { number: '118', service: 'fire' },
];
