import { VideoTrack } from '@livekit/react-native';
import { colors, fontFamily, radius, size, space } from '@solace/tokens/native';
import { router, useLocalSearchParams } from 'expo-router';
import {
  Mic,
  MicOff,
  PhoneOff,
  SwitchCamera,
  Video,
  VideoOff,
  Volume2,
  Wifi,
  WifiOff,
} from 'lucide-react-native';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ActivityIndicator, Linking, Pressable, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { useSessionLanguages } from '@/api/account';
import { type Booking, useBooking, useGuestToken } from '@/api/bookings';
import { ApiProblem } from '@/api/core';
import {
  joinSession,
  type JoinToken,
  raiseSafetyConcern,
  type SafetyCategory,
  useDialIn,
} from '@/api/sessions';
import { Chip } from '@/ui/Chip';
import { TextField } from '@/ui/TextField';
import { useAuth } from '@/auth/auth';
import { callEnded, callStarted, setUpCallKeep } from '@/call/callkeep';
import { checkMicrophone, useCall } from '@/call/engine';
import i18n from '@/i18n';
import { joinOpensAt, minutesOf } from '@/state/bookingView';
import { timeOf } from '@/state/format';
import { useNow } from '@/state/useNow';
import { useChooseScribe, useSessionScribe } from '@/api/care';
import { useFlags } from '@/config/flags';
import { Avatar } from '@/ui/Avatar';
import { SwitchRow } from '@/ui/SwitchRow';
import { Button } from '@/ui/Button';
import { BackButton } from '@/ui/ProgressSteps';
import { Text } from '@/ui/Text';

/** A join token request slower than this counts as a weak connection in the waiting room. */
const WEAK_MS = 1_500;

/**
 * A session's call (WaitingRoom, Call, VideoCall and SpecCall .dc.html): the waiting room checks the
 * microphone and the connection, then the same screen holds the call so nothing reconnects in
 * between. Seekers, guests and specialists use it; the other person is named from the booking,
 * never from the media server (D36).
 */
export default function SessionCall() {
  const { t } = useTranslation();
  const { id } = useLocalSearchParams<{ id: string }>();
  const lang = i18n.language === 'fr' ? 'fr' : 'en';
  const token = useGuestToken(id);
  const access = { bookingId: id, guestToken: token.data ?? undefined };
  const booking = useBooking(access, token.isSuccess);
  const languages = useSessionLanguages(lang);
  const call = useCall();
  const b = booking.data;

  if (!b) {
    return (
      <SafeAreaView style={[styles.screen, styles.center]}>
        <ActivityIndicator color={colors.brand} accessibilityLabel={t('common.loading')} />
      </SafeAreaView>
    );
  }
  const language =
    languages.data?.find((l) => l.code === b.sessionLanguage)?.name ?? b.sessionLanguage;
  // The specialist's view carries who the seeker is; the seeker's carries the specialist.
  const other = b.seeker ? b.seeker.label : b.specialist.fullName;

  if (call.status === 'idle') {
    return (
      <WaitingRoom booking={b} access={access} other={other} language={language} lang={lang} />
    );
  }
  if (call.status === 'ended') {
    return (
      <SafeAreaView style={[styles.screen, styles.center]}>
        <Text variant="titleMd" accessibilityRole="header">
          {t('call.ended')}
        </Text>
        <Text variant="body" color={colors.inkMuted}>
          {t('call.endedBody')}
        </Text>
        <Button
          label={t('call.back')}
          onPress={() => {
            useCall.setState({ status: 'idle' });
            router.replace({ pathname: '/session/[id]', params: { id } });
          }}
        />
      </SafeAreaView>
    );
  }
  return <InCall booking={b} other={other} language={language} lang={lang} />;
}

// --- Waiting room ---------------------------------------------------------------------------------

function WaitingRoom({
  booking: b,
  access,
  other,
  language,
  lang,
}: {
  booking: Booking;
  access: { bookingId: string; guestToken?: string };
  other: string;
  language: string;
  lang: string;
}) {
  const { t } = useTranslation();
  const connect = useCall((s) => s.connect);
  const [mic, setMic] = useState<boolean | null>(null);
  const [join, setJoin] = useState<JoinToken | null>(null);
  const [weak, setWeak] = useState<boolean | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const video = b.modality === 'VIDEO';
  const forSpecialist = Boolean(b.seeker);

  useEffect(() => {
    let live = true;
    void checkMicrophone().then((ok) => live && setMic(ok));
    const started = Date.now();
    joinSession(access)
      .then((token) => {
        if (!live) return;
        setJoin(token);
        setWeak(Date.now() - started > WEAK_MS);
      })
      .catch((e) => {
        if (!live) return;
        setWeak(true);
        setProblem(e instanceof ApiProblem && e.title ? e.title : t('common.checkConnection'));
      });
    return () => {
      live = false;
    };
    // The token is asked for once, when the waiting room opens.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const enter = async () => {
    if (!join || busy) return;
    setBusy(true);
    try {
      await setUpCallKeep(t('common.appName'));
      await connect(join);
      callStarted(b.id, t('common.appName'), video);
    } catch {
      setProblem(t('common.checkConnection'));
      useCall.setState({ status: 'idle' });
    } finally {
      setBusy(false);
    }
  };

  return (
    <SafeAreaView style={styles.screen} edges={['top', 'bottom']}>
      <View style={styles.content}>
        <View style={styles.titleRow}>
          <BackButton label={t('call.leave')} />
          <Text variant="titleMd" accessibilityRole="header">
            {t('call.waitingTitle')}
          </Text>
        </View>
        <View style={styles.card}>
          <Avatar name={other} />
          <View style={styles.flex}>
            <Text variant="label" style={styles.bold}>
              {t(forSpecialist ? 'call.seekerJoinsAt' : 'call.joinsAt', {
                name: other,
                time: timeOf(new Date(b.start), lang),
              })}
            </Text>
            <Text variant="meta" color={colors.inkMuted}>
              {t('call.meta', {
                modality: t(`book.modality_${b.modality}`),
                language,
                minutes: minutesOf(b),
              })}
            </Text>
          </View>
        </View>

        <Check
          ok={mic}
          label={
            mic === null ? t('call.micChecking') : mic ? t('call.micWorks') : t('call.micBlocked')
          }
          icon={mic === false ? MicOff : Mic}
        />
        <Check
          ok={weak === null ? null : !weak}
          label={
            weak === null
              ? t('call.connectionChecking')
              : weak
                ? t('call.connectionWeak')
                : t('call.connectionGood')
          }
          meta={
            weak === null
              ? undefined
              : weak
                ? t('call.weakAdvice')
                : t(video ? 'call.strongVideo' : 'call.strongAudio')
          }
          icon={weak ? WifiOff : Wifi}
        />
        {!forSpecialist ? <DialInCard access={access} /> : null}
        <ScribeChoice
          bookingId={b.id}
          guest={Boolean(access.guestToken)}
          forSpecialist={forSpecialist}
        />
        {problem ? (
          <Text variant="meta" color={colors.danger} accessibilityLiveRegion="polite">
            {problem}
          </Text>
        ) : null}
      </View>
      <View style={styles.footer}>
        <Button
          label={t('call.joinNow')}
          disabled={!join || busy || mic === false}
          accessibilityState={{ disabled: !join || busy || mic === false, busy }}
          onPress={enter}
        />
        {!join && !problem ? (
          <Text variant="meta" color={colors.inkMuted} style={styles.centerText}>
            {t('call.notOpen', { time: timeOf(joinOpensAt(b), lang) })}
          </Text>
        ) : null}
      </View>
    </SafeAreaView>
  );
}

/** Joining by phone for seekers without data (Phase 4, D59), when it is offered. */
function DialInCard({ access }: { access: { bookingId: string; guestToken?: string } }) {
  const { t } = useTranslation();
  const enabled = useFlags((f) => f.phoneDialIn);
  const dialIn = useDialIn(access, enabled);
  if (!enabled || !dialIn.data) return null;
  const { number, pin } = dialIn.data;
  return (
    <View style={styles.card}>
      <View style={styles.flex}>
        <Text variant="label" style={styles.bold}>
          {t('call.dialInTitle')}
        </Text>
        <Text variant="meta" color={colors.inkMuted}>
          {t('call.dialInBody', { number, pin })}
        </Text>
      </View>
      <Button
        variant="secondary"
        label={t('call.dialInCall')}
        accessibilityLabel={t('call.dialInCallLabel', { number })}
        onPress={() => void Linking.openURL(`tel:${number},,${pin}%23`)}
      />
    </View>
  );
}

/**
 * The session scribe for this session (D57): shown only when the scribe is on and both people
 * consented; either can turn it off for this session, and then nothing is recorded.
 */
function ScribeChoice({
  bookingId,
  guest,
  forSpecialist,
}: {
  bookingId: string;
  guest: boolean;
  forSpecialist: boolean;
}) {
  const { t } = useTranslation();
  const enabled = useFlags((f) => f.sessionScribe) && !guest;
  const state = useSessionScribe(bookingId, enabled);
  const choose = useChooseScribe(bookingId);
  const s = state.data;
  if (!enabled || !s || !s.available || !s.seekerConsented || !s.specialistConsented) {
    return (
      <Text variant="meta" color={colors.inkMuted}>
        {t('call.notRecorded')}
      </Text>
    );
  }
  const on = !s.refusedForSession;
  return (
    <SwitchRow
      title={t('scribe.title')}
      meta={on ? t(forSpecialist ? 'scribe.metaSpecialist' : 'scribe.meta') : t('scribe.off')}
      on={on}
      onToggle={() => choose.mutate(!on)}
    />
  );
}

function Check({
  ok,
  label,
  meta,
  icon: Icon,
}: {
  ok: boolean | null;
  label: string;
  meta?: string;
  icon: typeof Mic;
}) {
  const { t } = useTranslation();
  return (
    <View style={styles.check} accessible accessibilityLabel={meta ? `${label}, ${meta}` : label}>
      <Icon
        size={22}
        strokeWidth={1.8}
        color={ok === false ? colors.danger : colors.brand}
        aria-hidden
      />
      <View style={styles.flex}>
        <Text variant="label" style={styles.bold}>
          {label}
        </Text>
        {meta ? (
          <Text variant="meta" color={colors.inkMuted}>
            {meta}
          </Text>
        ) : null}
      </View>
      {ok ? (
        <Text variant="meta" color={colors.success} style={styles.bold}>
          {t('call.ok')}
        </Text>
      ) : ok === null ? (
        <ActivityIndicator color={colors.brand} />
      ) : null}
    </View>
  );
}

// --- In the call ----------------------------------------------------------------------------------

function InCall({
  booking: b,
  other,
  language,
  lang,
}: {
  booking: Booking;
  other: string;
  language: string;
  lang: string;
}) {
  const { t } = useTranslation();
  const call = useCall();
  const now = useNow(1_000);
  const elapsed = call.connectedAt ? Math.max(0, Math.floor((now - call.connectedAt) / 1000)) : 0;
  const clock = `${String(Math.floor(elapsed / 60)).padStart(2, '0')}:${String(elapsed % 60).padStart(2, '0')}`;
  const forSpecialist = Boolean(b.seeker);
  const showVideo = call.video && !call.audioOnly;
  const scribeOn = useFlags((f) => f.sessionScribe);
  // Guests have no member token and are never recorded.
  const signedIn = useAuth((s) => s.status === 'signedIn');
  const scribe = useSessionScribe(b.id, scribeOn && signedIn);
  const recording = Boolean(scribe.data?.recording);

  const end = async () => {
    callEnded(b.id);
    await call.leave();
  };

  const notices = (
    <>
      {call.status === 'reconnecting' ? <Notice text={t('call.reconnecting')} /> : null}
      {call.status !== 'reconnecting' && call.quality !== 'good' ? (
        <Notice text={t('call.weak')} />
      ) : null}
      {call.switchedAutomatically ? <Notice text={t('call.switchedToAudio')} /> : null}
    </>
  );

  return (
    <SafeAreaView style={[styles.screen, styles.dark]} edges={['top', 'bottom']}>
      {showVideo ? (
        <View style={styles.stage}>
          {call.remoteVideo ? (
            <View
              style={styles.remote}
              accessible
              accessibilityLabel={t('call.videoOf', { name: other })}
            >
              <VideoTrack trackRef={call.remoteVideo} style={styles.fill} objectFit="cover" />
            </View>
          ) : (
            <View style={[styles.remote, styles.center]}>
              <Avatar name={other} size={88} />
            </View>
          )}
          {call.localVideo ? (
            <View style={styles.preview} accessible accessibilityLabel={t('call.preview')}>
              <VideoTrack trackRef={call.localVideo} style={styles.fill} objectFit="cover" mirror />
            </View>
          ) : null}
          <View style={styles.overlayTop}>
            <Text variant="label" color={colors.onBrand} style={styles.bold}>
              {other}
            </Text>
            <Text variant="meta" color={colors.onBrandNight}>
              {t('call.videoMeta', { language })}
              {clock}
              {forSpecialist
                ? ` · ${t('call.endsAt', { time: timeOf(new Date(b.end), lang) })}`
                : ''}
            </Text>
            <Text variant="meta" color={colors.onBrandNight}>
              {recording ? t('scribe.on') : t('call.private')}
            </Text>
          </View>
        </View>
      ) : (
        <View style={styles.audioStage}>
          <Text variant="meta" color={colors.onBrandNight}>
            {recording ? t('scribe.on') : t('call.privateNotRecorded')}
          </Text>
          <Text style={styles.clock}>{clock}</Text>
          <Avatar name={other} size={104} />
          <Text variant="titleMd" color={colors.onBrand} accessibilityRole="header">
            {other}
          </Text>
          <Text variant="body" color={colors.onBrandNight}>
            {t('call.audioSession', { language })}
            {forSpecialist ? ` · ${t('call.endsAt', { time: timeOf(new Date(b.end), lang) })}` : ''}
          </Text>
          <Text variant="meta" color={colors.onBrandNight} accessibilityLiveRegion="polite">
            {!call.remotePresent
              ? t('call.waitingOther', { name: other })
              : call.remoteSpeaking
                ? t('call.speaking')
                : ' '}
          </Text>
        </View>
      )}

      <View style={styles.bottom}>
        {notices}
        {call.offerAudioOnly && showVideo ? (
          <Button
            variant="secondary"
            label={t('call.audioOnly')}
            onPress={() => call.goAudioOnly()}
          />
        ) : null}
        {forSpecialist ? <SafetyConcern bookingId={b.id} /> : null}
        <View style={styles.controls}>
          <Control
            label={call.micOn ? t('call.mute') : t('call.unmute')}
            icon={call.micOn ? Mic : MicOff}
            on={!call.micOn}
            onPress={() => call.toggleMic()}
          />
          {showVideo ? (
            <>
              <Control
                label={call.cameraOn ? t('call.cameraOff') : t('call.cameraOn')}
                icon={call.cameraOn ? Video : VideoOff}
                on={!call.cameraOn}
                onPress={() => call.toggleCamera()}
              />
              <Control
                label={t('call.flip')}
                icon={SwitchCamera}
                onPress={() => call.flipCamera()}
              />
            </>
          ) : (
            <Control
              label={t('call.speakerToggle')}
              icon={Volume2}
              on={call.speakerOn}
              onPress={() => call.toggleSpeaker()}
            />
          )}
          <Control label={t('call.end')} icon={PhoneOff} danger onPress={end} />
        </View>
      </View>
    </SafeAreaView>
  );
}

const CATEGORIES: SafetyCategory[] = [
  'SUICIDAL_IDEATION',
  'SELF_HARM',
  'ACUTE_ANXIETY',
  'VIOLENT_THOUGHTS',
  'SUBSTANCE_CRISIS',
  'RISK_TAKING',
  'UNKNOWN',
];

/**
 * Raise safety concern (SpecCall.dc.html): the specialist alerts Solace's on-call team from the
 * session. The alert reaches the crisis queue at once with the category and their short note.
 */
function SafetyConcern({ bookingId }: { bookingId: string }) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const [category, setCategory] = useState<SafetyCategory | null>(null);
  const [note, setNote] = useState('');
  const [state, setState] = useState<'idle' | 'sending' | 'sent' | 'failed'>('idle');

  const send = async () => {
    if (!category) return;
    setState('sending');
    try {
      await raiseSafetyConcern(bookingId, category, note);
      setState('sent');
      setOpen(false);
    } catch {
      setState('failed');
    }
  };

  if (state === 'sent') return <Notice text={t('call.concernSent')} />;
  if (!open) {
    return (
      <Button variant="secondary" label={t('call.raiseConcern')} onPress={() => setOpen(true)} />
    );
  }
  return (
    <View style={styles.concern}>
      <Text variant="label" style={styles.bold} accessibilityRole="header">
        {t('call.raiseConcern')}
      </Text>
      <Text variant="meta" color={colors.inkMuted}>
        {t('call.concernBody')}
      </Text>
      <View style={styles.concernChips}>
        {CATEGORIES.map((c) => (
          <Chip
            key={c}
            role="radio"
            label={t(`call.concern_${c}`)}
            on={category === c}
            onToggle={() => setCategory(c)}
          />
        ))}
      </View>
      <TextField
        label={t('call.concernNote')}
        value={note}
        onChangeText={setNote}
        maxLength={500}
        multiline
        error={state === 'failed' ? t('common.checkConnection') : undefined}
      />
      <Button
        label={t('call.concernSend')}
        disabled={!category || state === 'sending'}
        accessibilityState={{
          disabled: !category || state === 'sending',
          busy: state === 'sending',
        }}
        onPress={() => void send()}
      />
      <Button variant="quiet" label={t('call.concernCancel')} onPress={() => setOpen(false)} />
    </View>
  );
}

function Notice({ text }: { text: string }) {
  return (
    <View style={styles.notice} accessibilityLiveRegion="polite">
      <Text variant="meta" color={colors.warningInk}>
        {text}
      </Text>
    </View>
  );
}

function Control({
  label,
  icon: Icon,
  on = false,
  danger = false,
  onPress,
}: {
  label: string;
  icon: typeof Mic;
  on?: boolean;
  danger?: boolean;
  onPress: () => void;
}) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{ selected: on }}
      onPress={onPress}
      style={[styles.control, on && styles.controlOn, danger && styles.controlDanger]}
    >
      <Icon size={24} strokeWidth={1.8} color={on ? colors.ink : colors.onBrand} aria-hidden />
    </Pressable>
  );
}

const styles = StyleSheet.create({
  concern: {
    gap: space.space8,
    padding: space.space14,
    borderRadius: radius.radius14,
    backgroundColor: colors.surfaceCard,
  },
  concernChips: { flexDirection: 'row', flexWrap: 'wrap', gap: space.space8 },
  screen: {
    flex: 1,
    backgroundColor: colors.surfacePage,
  },
  dark: {
    backgroundColor: colors.brandNight,
  },
  center: {
    alignItems: 'center',
    justifyContent: 'center',
    gap: space.space14,
    padding: space.space24,
  },
  centerText: {
    textAlign: 'center',
  },
  content: {
    flex: 1,
    padding: space.space20,
    gap: space.space16,
  },
  titleRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space4,
  },
  card: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space12,
    padding: space.space16,
    borderRadius: radius.radius16,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  check: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.space12,
    padding: space.space14,
    borderRadius: radius.radius14,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  flex: {
    flex: 1,
  },
  bold: {
    fontFamily: fontFamily.body[700],
  },
  footer: {
    gap: space.space8,
    paddingHorizontal: space.space20,
    paddingTop: space.space16,
    paddingBottom: space.space14,
    borderTopWidth: 1,
    borderTopColor: colors.line,
    backgroundColor: colors.surfaceCard,
  },
  stage: {
    flex: 1,
  },
  remote: {
    flex: 1,
    backgroundColor: colors.brandNight,
  },
  fill: {
    flex: 1,
  },
  preview: {
    position: 'absolute',
    right: space.space16,
    bottom: space.space16,
    width: 104,
    height: 148,
    borderRadius: radius.radius14,
    overflow: 'hidden',
    borderWidth: 1,
    borderColor: colors.brandLineOnDark,
  },
  overlayTop: {
    position: 'absolute',
    top: space.space16,
    left: space.space16,
    gap: space.space2,
  },
  audioStage: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    gap: space.space12,
    padding: space.space24,
  },
  clock: {
    fontFamily: fontFamily.display[600],
    fontSize: 20,
    color: colors.onBrand,
  },
  bottom: {
    gap: space.space12,
    padding: space.space20,
  },
  notice: {
    padding: space.space12,
    borderRadius: radius.radius12,
    backgroundColor: colors.warningTint,
  },
  controls: {
    flexDirection: 'row',
    justifyContent: 'center',
    gap: space.space16,
  },
  control: {
    width: 60,
    height: 60,
    minHeight: size.sizeTap,
    borderRadius: 30,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: colors.brandCallControl,
  },
  controlOn: {
    backgroundColor: colors.onBrand,
  },
  controlDanger: {
    backgroundColor: colors.dangerCallEnd,
  },
});
