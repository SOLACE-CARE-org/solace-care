import { AudioSession } from '@livekit/react-native';
import {
  ConnectionQuality,
  createLocalAudioTrack,
  type LocalVideoTrack,
  type Participant,
  type RemoteParticipant,
  Room,
  RoomEvent,
  Track,
  type TrackPublication,
  VideoPresets,
} from 'livekit-client';
import { Platform } from 'react-native';
import { create } from 'zustand';

import type { JoinToken } from '@/api/sessions';

/** Below "poor" for this long, a video session offers audio only (System Design, Weak networks). */
const POOR_OFFER_MS = 10_000;

export type Quality = 'good' | 'poor' | 'lost';

/** A track to draw, in the shape LiveKit's VideoTrack component takes. */
export type TrackRef = {
  participant: Participant;
  publication: TrackPublication;
  source: Track.Source;
};

type CallState = {
  status: 'idle' | 'connecting' | 'connected' | 'reconnecting' | 'ended';
  video: boolean;
  /** The other person is in the room. */
  remotePresent: boolean;
  remoteSpeaking: boolean;
  quality: Quality;
  micOn: boolean;
  cameraOn: boolean;
  speakerOn: boolean;
  /** Stepped down to audio: no video sent or received. */
  audioOnly: boolean;
  /** Poor for 10 seconds: the screen offers audio only. */
  offerAudioOnly: boolean;
  /** Stepped down without being asked, below the hard floor. */
  switchedAutomatically: boolean;
  remoteVideo: TrackRef | null;
  localVideo: TrackRef | null;
  connectedAt: number | null;
  connect: (join: JoinToken) => Promise<void>;
  toggleMic: () => Promise<void>;
  toggleCamera: () => Promise<void>;
  toggleSpeaker: () => Promise<void>;
  flipCamera: () => Promise<void>;
  goAudioOnly: (automatic?: boolean) => Promise<void>;
  leave: () => Promise<void>;
};

let room: Room | null = null;
let poorSince: number | null = null;
let facing: 'user' | 'environment' = 'user';

const initial = {
  status: 'idle' as const,
  video: false,
  remotePresent: false,
  remoteSpeaking: false,
  quality: 'good' as Quality,
  micOn: true,
  cameraOn: false,
  speakerOn: false,
  audioOnly: false,
  offerAudioOnly: false,
  switchedAutomatically: false,
  remoteVideo: null,
  localVideo: null,
  connectedAt: null,
};

function trackRef(p: Participant | undefined, source: Track.Source): TrackRef | null {
  const publication = p?.getTrackPublication(source);
  return p && publication?.track ? { participant: p, publication, source } : null;
}

function qualityOf(q: ConnectionQuality): Quality {
  if (q === ConnectionQuality.Lost) return 'lost';
  if (q === ConnectionQuality.Poor) return 'poor';
  return 'good';
}

/**
 * One session's call (LiveKit). Audio sessions never publish video; video sessions start low and
 * step up (simulcast, adaptive stream, dynacast), offer audio only after 10 poor seconds and switch
 * on their own when the connection is lost. LiveKit reconnects by itself; the room keeps the place
 * for 2 minutes (D18).
 */
export const useCall = create<CallState>()((set, get) => {
  const refresh = () => {
    if (!room) return;
    const remote: RemoteParticipant | undefined = [...room.remoteParticipants.values()][0];
    const { audioOnly } = get();
    set({
      remotePresent: Boolean(remote),
      remoteVideo: audioOnly ? null : trackRef(remote, Track.Source.Camera),
      localVideo: audioOnly ? null : trackRef(room.localParticipant, Track.Source.Camera),
      micOn: room.localParticipant.isMicrophoneEnabled,
      cameraOn: room.localParticipant.isCameraEnabled,
    });
  };

  const onQuality = (q: ConnectionQuality, p: Participant) => {
    if (!room || p !== room.localParticipant) return;
    const quality = qualityOf(q);
    const { video, audioOnly } = get();
    set({ quality });
    if (!video || audioOnly) return;
    if (quality === 'lost') {
      void get().goAudioOnly(true);
    } else if (quality === 'poor') {
      poorSince ??= Date.now();
      if (Date.now() - poorSince >= POOR_OFFER_MS) set({ offerAudioOnly: true });
    } else {
      poorSince = null;
    }
  };

  return {
    ...initial,

    connect: async (join) => {
      const video = join.modality === 'VIDEO';
      set({ ...initial, status: 'connecting', video, cameraOn: video });
      await AudioSession.startAudioSession();
      room = new Room({
        adaptiveStream: true,
        dynacast: true,
        videoCaptureDefaults: { resolution: VideoPresets.h360.resolution },
        publishDefaults: { simulcast: true, videoSimulcastLayers: [VideoPresets.h180] },
        disconnectOnPageLeave: false,
      });
      room
        .on(RoomEvent.ParticipantConnected, refresh)
        .on(RoomEvent.ParticipantDisconnected, refresh)
        .on(RoomEvent.TrackSubscribed, refresh)
        .on(RoomEvent.TrackUnsubscribed, refresh)
        .on(RoomEvent.LocalTrackPublished, refresh)
        .on(RoomEvent.LocalTrackUnpublished, refresh)
        .on(RoomEvent.ActiveSpeakersChanged, (speakers) =>
          set({ remoteSpeaking: speakers.some((s) => s !== room?.localParticipant) }),
        )
        .on(RoomEvent.ConnectionQualityChanged, onQuality)
        .on(RoomEvent.Reconnecting, () => set({ status: 'reconnecting' }))
        .on(RoomEvent.Reconnected, () => set({ status: 'connected' }))
        .on(RoomEvent.Disconnected, () => {
          set({ status: 'ended' });
          void AudioSession.stopAudioSession();
        });
      await room.connect(join.url, join.token, { autoSubscribe: true });
      await room.localParticipant.setMicrophoneEnabled(true);
      if (video) await room.localParticipant.setCameraEnabled(true, { facingMode: facing });
      set({ status: 'connected', connectedAt: Date.now() });
      refresh();
    },

    toggleMic: async () => {
      if (!room) return;
      await room.localParticipant.setMicrophoneEnabled(!room.localParticipant.isMicrophoneEnabled);
      refresh();
    },

    toggleCamera: async () => {
      if (!room || !get().video || get().audioOnly) return;
      await room.localParticipant.setCameraEnabled(!room.localParticipant.isCameraEnabled);
      refresh();
    },

    toggleSpeaker: async () => {
      const on = !get().speakerOn;
      try {
        await AudioSession.selectAudioOutput(
          Platform.OS === 'ios' ? (on ? 'force_speaker' : 'default') : on ? 'speaker' : 'earpiece',
        );
      } catch {
        // Some devices choose for themselves.
      }
      set({ speakerOn: on });
    },

    flipCamera: async () => {
      const track = room?.localParticipant.getTrackPublication(Track.Source.Camera)?.track as
        LocalVideoTrack | undefined;
      if (!track) return;
      facing = facing === 'user' ? 'environment' : 'user';
      await track.restartTrack({ facingMode: facing });
    },

    goAudioOnly: async (automatic = false) => {
      if (!room) return;
      await room.localParticipant.setCameraEnabled(false);
      for (const p of room.remoteParticipants.values()) {
        p.getTrackPublication(Track.Source.Camera)?.setSubscribed(false);
      }
      set({ audioOnly: true, offerAudioOnly: false, switchedAutomatically: automatic });
      refresh();
    },

    leave: async () => {
      const current = room;
      room = null;
      poorSince = null;
      set({ status: 'ended' });
      await current?.disconnect();
      await AudioSession.stopAudioSession();
    },
  };
});

/** The waiting room's microphone check: true when a microphone can be opened. */
export async function checkMicrophone(): Promise<boolean> {
  try {
    const track = await createLocalAudioTrack();
    track.stop();
    return true;
  } catch {
    return false;
  }
}
