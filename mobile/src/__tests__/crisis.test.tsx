import { fireEvent } from '@testing-library/react-native';
import { renderRouter } from 'expo-router/testing-library';
import { Linking } from 'react-native';

import UrgentHelp from '@/app/crisis';
import { initI18n } from '@/i18n';

jest.setTimeout(30_000);

describe('Urgent help', () => {
  beforeEach(async () => {
    await initI18n('fr');
    // No network at all: nothing on this screen may need it (System Design, Crisis button).
    globalThis.fetch = jest.fn(async () => {
      throw new Error('offline');
    }) as typeof fetch;
  });

  it('shows the emergency numbers offline and calls one in a tap', async () => {
    const open = jest.spyOn(Linking, 'openURL').mockResolvedValue(undefined);
    const screen = await renderRouter({ crisis: UrgentHelp }, { initialUrl: '/crisis' });

    expect(await screen.findByText('Aide urgente')).toBeTruthy();
    expect(screen.getByText("Vous n'avez pas à affronter cela sans aide.")).toBeTruthy();
    expect(screen.getByText("Les numéros d'urgence fonctionnent même sans internet.")).toBeTruthy();

    await fireEvent.press(screen.getByRole('button', { name: 'Appeler le 119, Ambulance (SAMU)' }));
    expect(open).toHaveBeenCalledWith('tel:119');
    expect(screen.getByRole('button', { name: 'Appeler le 117, Police' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Appeler le 118, Pompiers' })).toBeTruthy();
    // Behind its flag until the service exists (D39).
    expect(screen.queryByText('Parler à un spécialiste de garde')).toBeNull();
    expect(globalThis.fetch).not.toHaveBeenCalled();
  });

  it('starts the breathing exercise', async () => {
    const screen = await renderRouter({ crisis: UrgentHelp }, { initialUrl: '/crisis' });

    await fireEvent.press(await screen.findByRole('button', { name: 'Commencer' }));
    expect(await screen.findByText('Inspirez')).toBeTruthy();
  });
});
