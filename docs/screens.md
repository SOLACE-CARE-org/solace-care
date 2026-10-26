# Screen index

Every designed screen, with its source file and a PNG render. EN and FR versions exist for every screen: the FR file adds `_fr` (e.g. `Search_fr.dc.html`, `png/Search_fr.png`).
Phase = the build phase in `docs/specs/implementation-plan.md`. Build a screen in the same phase as the backend epics it calls.

PNG renders use fallback fonts and a dashed "logo" placeholder: the real faces are Bricolage Grotesque and Figtree, and the logos are in `design/design-system/assets/Logos/`.

## Mobile app (React Native, 390 × 844)


### Start & sign-up

| Screen | Phase | Source | Render | Note |
| --- | --- | --- | --- | --- |
| Splash | 1 | `design/screens/mobile/source/Splash.dc.html` | `design/screens/mobile/png/Splash.png` |  |
| Choose language (EN/FR) | 1 | `design/screens/mobile/source/Language.dc.html` | `design/screens/mobile/png/Language.png` |  |
| Intro 1: talk to a specialist | 1 | `design/screens/mobile/source/Onboard1.dc.html` | `design/screens/mobile/png/Onboard1.png` |  |
| Intro 2: book without an account | 1 | `design/screens/mobile/source/Onboard2.dc.html` | `design/screens/mobile/png/Onboard2.png` |  |
| Intro 3: track privately | 1 | `design/screens/mobile/source/Onboard3.dc.html` | `design/screens/mobile/png/Onboard3.png` |  |
| Reminders permission | 1 | `design/screens/mobile/source/AllowReminders.dc.html` | `design/screens/mobile/png/AllowReminders.png` |  |
| Welcome (EN/FR) | 1 | `design/screens/mobile/source/Main.dc.html` | `design/screens/mobile/png/Main.png` | Admin sign-in; first sign-in is FirstSignIn on the States page. |
| Choose account type | 1 | `design/screens/mobile/source/RoleChoice.dc.html` | `design/screens/mobile/png/RoleChoice.png` |  |
| Sign in | 1 | `design/screens/mobile/source/SignIn.dc.html` | `design/screens/mobile/png/SignIn.png` |  |
| Verify code | 1 | `design/screens/mobile/source/VerifyCode.dc.html` | `design/screens/mobile/png/VerifyCode.png` |  |
| Seeker sign-up: details | 1 | `design/screens/mobile/source/SeekerSignup.dc.html` | `design/screens/mobile/png/SeekerSignup.png` |  |
| Seeker sign-up: preferences | 1 | `design/screens/mobile/source/SeekerPreferences.dc.html` | `design/screens/mobile/png/SeekerPreferences.png` |  |
| Seeker sign-up: privacy choices | 1 | `design/screens/mobile/source/SeekerConsent.dc.html` | `design/screens/mobile/png/SeekerConsent.png` | Health check and tracking consent rows appear only when those phases ship. |
| Seeker sign-up: tracking | 2 | `design/screens/mobile/source/SeekerModules.dc.html` | `design/screens/mobile/png/SeekerModules.png` |  |
| Seeker sign-up: done | 1 | `design/screens/mobile/source/SeekerReady.dc.html` | `design/screens/mobile/png/SeekerReady.png` |  |
| Specialist: account | 1 | `design/screens/mobile/source/SpecSignup.dc.html` | `design/screens/mobile/png/SpecSignup.png` |  |
| Specialist: credentials | 1 | `design/screens/mobile/source/SpecProfessional.dc.html` | `design/screens/mobile/png/SpecProfessional.png` |  |
| Specialist: languages | 1 | `design/screens/mobile/source/SpecLanguages.dc.html` | `design/screens/mobile/png/SpecLanguages.png` |  |
| Specialist: documents | 1 | `design/screens/mobile/source/SpecDocuments.dc.html` | `design/screens/mobile/png/SpecDocuments.png` |  |
| Specialist: services & prices | 1 | `design/screens/mobile/source/SpecServices.dc.html` | `design/screens/mobile/png/SpecServices.png` |  |
| Specialist: weekly hours | 1 | `design/screens/mobile/source/SpecAvailability.dc.html` | `design/screens/mobile/png/SpecAvailability.png` |  |
| Specialist: payouts | 1 | `design/screens/mobile/source/SpecPayout.dc.html` | `design/screens/mobile/png/SpecPayout.png` |  |
| Specialist: under review | 1 | `design/screens/mobile/source/SpecReview.dc.html` | `design/screens/mobile/png/SpecReview.png` |  |

### Seeker

| Screen | Phase | Source | Render | Note |
| --- | --- | --- | --- | --- |
| Health check: start | 2 | `design/screens/mobile/source/HealthCheckIntro.dc.html` | `design/screens/mobile/png/HealthCheckIntro.png` |  |
| Health check: topics | 2 | `design/screens/mobile/source/HealthCheckTopics.dc.html` | `design/screens/mobile/png/HealthCheckTopics.png` |  |
| Health check: screener question | 2 | `design/screens/mobile/source/HealthCheckScale.dc.html` | `design/screens/mobile/png/HealthCheckScale.png` |  |
| Health check: urgent help (safety answer) | 2 | `design/screens/mobile/source/HealthCheckUrgent.dc.html` | `design/screens/mobile/png/HealthCheckUrgent.png` |  |
| Health check: preferences | 2 | `design/screens/mobile/source/HealthCheckPrefs.dc.html` | `design/screens/mobile/png/HealthCheckPrefs.png` |  |
| Health check: your suggestion | 2 | `design/screens/mobile/source/HealthCheckResult.dc.html` | `design/screens/mobile/png/HealthCheckResult.png` |  |
| Matches from the health check | 2 | `design/screens/mobile/source/Matches.dc.html` | `design/screens/mobile/png/Matches.png` |  |
| Find a specialist | 1 | `design/screens/mobile/source/Search.dc.html` | `design/screens/mobile/png/Search.png` | The health check card on this screen ships in Phase 2. |
| Specialist profile | 1 | `design/screens/mobile/source/Specialist.dc.html` | `design/screens/mobile/png/Specialist.png` |  |
| Choose a session | 1 | `design/screens/mobile/source/Book.dc.html` | `design/screens/mobile/png/Book.png` |  |
| Verify and pay (guest) | 1 | `design/screens/mobile/source/Checkout.dc.html` | `design/screens/mobile/png/Checkout.png` |  |
| Approve payment | 1 | `design/screens/mobile/source/PaymentPending.dc.html` | `design/screens/mobile/png/PaymentPending.png` |  |
| Booking confirmed | 1 | `design/screens/mobile/source/BookingConfirmed.dc.html` | `design/screens/mobile/png/BookingConfirmed.png` |  |
| My sessions | 1 | `design/screens/mobile/source/Sessions.dc.html` | `design/screens/mobile/png/Sessions.png` |  |
| Session details | 1 | `design/screens/mobile/source/SessionDetail.dc.html` | `design/screens/mobile/png/SessionDetail.png` |  |
| Cancel or reschedule | 1 | `design/screens/mobile/source/CancelSession.dc.html` | `design/screens/mobile/png/CancelSession.png` |  |
| Guest: open booking from SMS | 1 | `design/screens/mobile/source/GuestJoin.dc.html` | `design/screens/mobile/png/GuestJoin.png` |  |
| Waiting room | 1 | `design/screens/mobile/source/WaitingRoom.dc.html` | `design/screens/mobile/png/WaitingRoom.png` |  |
| In session (audio) | 1 | `design/screens/mobile/source/Call.dc.html` | `design/screens/mobile/png/Call.png` |  |
| In session (video) | 1 | `design/screens/mobile/source/VideoCall.dc.html` | `design/screens/mobile/png/VideoCall.png` |  |
| Rate your session | 1 | `design/screens/mobile/source/RateSession.dc.html` | `design/screens/mobile/png/RateSession.png` |  |
| Seeker home | 1 | `design/screens/mobile/source/Home.dc.html` | `design/screens/mobile/png/Home.png` | Tracking cards ship in Phase 2; Phase 1 Home shows sessions only. |
| Track tab | 2 | `design/screens/mobile/source/TrackHub.dc.html` | `design/screens/mobile/png/TrackHub.png` |  |
| Add a tracking module | 2 | `design/screens/mobile/source/ModuleCatalog.dc.html` | `design/screens/mobile/png/ModuleCatalog.png` |  |
| Log today (PCOS & cycle) | 2 | `design/screens/mobile/source/Tracking.dc.html` | `design/screens/mobile/png/Tracking.png` |  |
| Log today (daily check-in) | 2 | `design/screens/mobile/source/Checkin.dc.html` | `design/screens/mobile/png/Checkin.png` |  |
| Daily check-in trends | 2 | `design/screens/mobile/source/Trends.dc.html` | `design/screens/mobile/png/Trends.png` |  |
| Recommendation | 2 | `design/screens/mobile/source/Insights.dc.html` | `design/screens/mobile/png/Insights.png` |  |
| Companion & homework | 4 | `design/screens/mobile/source/Companion.dc.html` | `design/screens/mobile/png/Companion.png` |  |
| Urgent help | 1 | `design/screens/mobile/source/Crisis.dc.html` | `design/screens/mobile/png/Crisis.png` |  |
| Safety plan | 4 | `design/screens/mobile/source/SafetyPlan.dc.html` | `design/screens/mobile/png/SafetyPlan.png` |  |
| You tab | 1 | `design/screens/mobile/source/Profile.dc.html` | `design/screens/mobile/png/Profile.png` |  |
| Edit profile | 1 | `design/screens/mobile/source/EditProfile.dc.html` | `design/screens/mobile/png/EditProfile.png` |  |
| Privacy & data | 1 | `design/screens/mobile/source/PrivacyCentre.dc.html` | `design/screens/mobile/png/PrivacyCentre.png` | Health check and tracking rows appear only when those phases ship. |
| Notifications | 1 | `design/screens/mobile/source/Notifications.dc.html` | `design/screens/mobile/png/Notifications.png` |  |
| Settings | 1 | `design/screens/mobile/source/Settings.dc.html` | `design/screens/mobile/png/Settings.png` |  |

### Specialist

| Screen | Phase | Source | Render | Note |
| --- | --- | --- | --- | --- |
| Today | 1 | `design/screens/mobile/source/SpecialistToday.dc.html` | `design/screens/mobile/png/SpecialistToday.png` | The brief link ships in Phase 3. |
| Calendar & availability | 1 | `design/screens/mobile/source/SpecCalendar.dc.html` | `design/screens/mobile/png/SpecCalendar.png` |  |
| Pre-session brief | 3 | `design/screens/mobile/source/SessionBrief.dc.html` | `design/screens/mobile/png/SessionBrief.png` |  |
| In session (specialist, video) | 1 | `design/screens/mobile/source/SpecCall.dc.html` | `design/screens/mobile/png/SpecCall.png` |  |
| Notes | 4 | `design/screens/mobile/source/NotesList.dc.html` | `design/screens/mobile/png/NotesList.png` |  |
| Review AI note draft | 4 | `design/screens/mobile/source/NoteReview.dc.html` | `design/screens/mobile/png/NoteReview.png` |  |
| Assign homework | 4 | `design/screens/mobile/source/AssignHomework.dc.html` | `design/screens/mobile/png/AssignHomework.png` |  |
| Earnings & payouts | 1 | `design/screens/mobile/source/Earnings.dc.html` | `design/screens/mobile/png/Earnings.png` |  |
| Reviews | 1 | `design/screens/mobile/source/Reviews.dc.html` | `design/screens/mobile/png/Reviews.png` |  |
| Profile tab | 1 | `design/screens/mobile/source/SpecProfile.dc.html` | `design/screens/mobile/png/SpecProfile.png` |  |

### States

| Screen | Phase | Source | Render | Note |
| --- | --- | --- | --- | --- |
| Loading specialists | 1 | `design/screens/mobile/source/StateSearchLoading.dc.html` | `design/screens/mobile/png/StateSearchLoading.png` |  |
| No specialists match | 1 | `design/screens/mobile/source/StateSearchEmpty.dc.html` | `design/screens/mobile/png/StateSearchEmpty.png` |  |
| No sessions yet | 1 | `design/screens/mobile/source/StateSessionsEmpty.dc.html` | `design/screens/mobile/png/StateSessionsEmpty.png` |  |
| Not tracking yet | 2 | `design/screens/mobile/source/StateTrackEmpty.dc.html` | `design/screens/mobile/png/StateTrackEmpty.png` |  |
| No sessions today | 1 | `design/screens/mobile/source/StateSpecialistQuietDay.dc.html` | `design/screens/mobile/png/StateSpecialistQuietDay.png` |  |
| Could not load | 1 | `design/screens/mobile/source/StateLoadError.dc.html` | `design/screens/mobile/png/StateLoadError.png` |  |
| Payment did not go through | 1 | `design/screens/mobile/source/StatePaymentFailed.dc.html` | `design/screens/mobile/png/StatePaymentFailed.png` |  |
| Wrong code | 1 | `design/screens/mobile/source/StateCodeWrong.dc.html` | `design/screens/mobile/png/StateCodeWrong.png` |  |
| Microphone blocked | 1 | `design/screens/mobile/source/StateMicBlocked.dc.html` | `design/screens/mobile/png/StateMicBlocked.png` |  |
| Reconnecting | 1 | `design/screens/mobile/source/StateCallReconnecting.dc.html` | `design/screens/mobile/png/StateCallReconnecting.png` |  |
| Home while offline | 1 | `design/screens/mobile/source/StateOfflineHome.dc.html` | `design/screens/mobile/png/StateOfflineHome.png` |  |
| Saved offline | 2 | `design/screens/mobile/source/StateLogSavedOffline.dc.html` | `design/screens/mobile/png/StateLogSavedOffline.png` |  |

## Admin portal (Angular, 1440 wide)


### Launch (Phase 1)

| Screen | Phase | Source | Render | Note |
| --- | --- | --- | --- | --- |
| 1 · Sign in | 1 | `design/screens/admin/source/Main.dc.html` | `design/screens/admin/png/Main.png` | Staff sign-in. First sign-in (new password + authenticator) is `FirstSignIn`. |
| 2 · Overview | 1 | `design/screens/admin/source/Overview.dc.html` | `design/screens/admin/png/Overview.png` |  |
| 3 · Crisis queue | 1 | `design/screens/admin/source/CrisisQueue.dc.html` | `design/screens/admin/png/CrisisQueue.png` |  |
| 3 · Crisis queue on a phone | 1 | `design/screens/admin/source/CrisisPhone.dc.html` | `design/screens/admin/png/CrisisPhone.png` |  |
| Sidebar (shared) | 1 | `design/screens/admin/source/Sidebar.dc.html` | `design/screens/admin/png/Sidebar.png` | Shared component, imported by every admin screen |
| 4 · Specialist applications | 1 | `design/screens/admin/source/Applications.dc.html` | `design/screens/admin/png/Applications.png` |  |
| 5 · Application review | 1 | `design/screens/admin/source/ApplicationReview.dc.html` | `design/screens/admin/png/ApplicationReview.png` |  |
| 6 · Specialist record | 1 | `design/screens/admin/source/SpecialistRecord.dc.html` | `design/screens/admin/png/SpecialistRecord.png` |  |
| 9 · People | 1 | `design/screens/admin/source/People.dc.html` | `design/screens/admin/png/People.png` |  |
| 10 · Person record | 1 | `design/screens/admin/source/PersonRecord.dc.html` | `design/screens/admin/png/PersonRecord.png` |  |
| 7 · Bookings | 1 | `design/screens/admin/source/Bookings.dc.html` | `design/screens/admin/png/Bookings.png` |  |
| 8 · Booking detail | 1 | `design/screens/admin/source/BookingDetail.dc.html` | `design/screens/admin/png/BookingDetail.png` |  |
| 13 · Reviews | 1 | `design/screens/admin/source/Reviews.dc.html` | `design/screens/admin/png/Reviews.png` |  |
| 11 · Payments | 1 | `design/screens/admin/source/Payments.dc.html` | `design/screens/admin/png/Payments.png` |  |
| 12 · Payout run | 1 | `design/screens/admin/source/PayoutRun.dc.html` | `design/screens/admin/png/PayoutRun.png` |  |
| 14 · Catalogue and policies | 1 | `design/screens/admin/source/Catalogue.dc.html` | `design/screens/admin/png/Catalogue.png` |  |
| 15 · Content | 1 | `design/screens/admin/source/Content.dc.html` | `design/screens/admin/png/Content.png` |  |
| 16 · Content editor | 1 | `design/screens/admin/source/ContentEditor.dc.html` | `design/screens/admin/png/ContentEditor.png` |  |
| 17 · Analytics | 1 | `design/screens/admin/source/Analytics.dc.html` | `design/screens/admin/png/Analytics.png` |  |
| 18 · Audit log | 1 | `design/screens/admin/source/AuditLog.dc.html` | `design/screens/admin/png/AuditLog.png` |  |
| 19 · Team and roles | 1 | `design/screens/admin/source/Team.dc.html` | `design/screens/admin/png/Team.png` |  |
| 20 · Settings and flags | 1 | `design/screens/admin/source/Settings.dc.html` | `design/screens/admin/png/Settings.png` |  |

### Tracking and health check (Phase 2)

| Screen | Phase | Source | Render | Note |
| --- | --- | --- | --- | --- |
| 21 · Tracking modules | 2 | `design/screens/admin/source/Modules.dc.html` | `design/screens/admin/png/Modules.png` |  |
| 22 · Builder: details | 2 | `design/screens/admin/source/ModuleDetails.dc.html` | `design/screens/admin/png/ModuleDetails.png` |  |
| 23 · Builder: elements | 2 | `design/screens/admin/source/ModuleElements.dc.html` | `design/screens/admin/png/ModuleElements.png` |  |
| 24 · Builder: rules | 2 | `design/screens/admin/source/ModuleRules.dc.html` | `design/screens/admin/png/ModuleRules.png` |  |
| 25 · Clinical review | 2 | `design/screens/admin/source/ModuleReview.dc.html` | `design/screens/admin/png/ModuleReview.png` |  |
| 28 · Health check | 2 | `design/screens/admin/source/HealthCheck.dc.html` | `design/screens/admin/png/HealthCheck.png` |  |
| 29 · Health check: questions | 2 | `design/screens/admin/source/HealthCheckQuestions.dc.html` | `design/screens/admin/png/HealthCheckQuestions.png` |  |
| 30 · Health check: scoring | 2 | `design/screens/admin/source/HealthCheckScoring.dc.html` | `design/screens/admin/png/HealthCheckScoring.png` |  |
| 31 · Health check: test and review | 2 | `design/screens/admin/source/HealthCheckReview.dc.html` | `design/screens/admin/png/HealthCheckReview.png` |  |

### AI (Phases 3 and 4)

| Screen | Phase | Source | Render | Note |
| --- | --- | --- | --- | --- |
| 26 · AI controls | 3 | `design/screens/admin/source/AIControls.dc.html` | `design/screens/admin/png/AIControls.png` |  |
| 27 · Admin copilot | 4 | `design/screens/admin/source/Copilot.dc.html` | `design/screens/admin/png/Copilot.png` |  |

### Configuration (D61)

| Screen | Phase | Source | Render | Note |
| --- | --- | --- | --- | --- |
| 32 · Payment providers and routes | 1 | `design/screens/admin/source/PaymentProviders.dc.html` | `design/screens/admin/png/PaymentProviders.png` | Opened from Payments |
| 32b · Activate a live provider | 1 | `design/screens/admin/source/PaymentProviderActivate.dc.html` | `design/screens/admin/png/PaymentProviderActivate.png` | The four checks before a live provider becomes active |
| 33 · SMS and email senders | 1 | `design/screens/admin/source/MessageSenders.dc.html` | `design/screens/admin/png/MessageSenders.png` | Opened from Settings and flags |
| 34 · Scheduled jobs | 1 | `design/screens/admin/source/Jobs.dc.html` | `design/screens/admin/png/Jobs.png` | Opened from Settings and flags; Core, Tracking and AI tabs |
| 34b · Change a job safety depends on | 1 | `design/screens/admin/source/JobPause.dc.html` | `design/screens/admin/png/JobPause.png` |  |
| 35 · Notification copy | 1 | `design/screens/admin/source/NotificationCopy.dc.html` | `design/screens/admin/png/NotificationCopy.png` | Opened from Content |
| 36 · AI models and routes | 3 | `design/screens/admin/source/AIModels.dc.html` | `design/screens/admin/png/AIModels.png` | Opened from AI controls |
| 37 · Reviewed lists | 2 (Tracking), 3 (AI) | `design/screens/admin/source/ReviewedLists.dc.html` | `design/screens/admin/png/ReviewedLists.png` | Opened from AI controls and from Tracking modules; the board shows the AI lists |

### States

| Screen | Phase | Source | Render | Note |
| --- | --- | --- | --- | --- |
| Bookings loading | 1 | `design/screens/admin/source/StateAdminLoading.dc.html` | `design/screens/admin/png/StateAdminLoading.png` |  |
| No open alerts | 1 | `design/screens/admin/source/StateAdminCrisisEmpty.dc.html` | `design/screens/admin/png/StateAdminCrisisEmpty.png` |  |
| Payments partly loaded | 1 | `design/screens/admin/source/StateAdminError.dc.html` | `design/screens/admin/png/StateAdminError.png` |  |
| No access | 1 | `design/screens/admin/source/StateAdminNoAccess.dc.html` | `design/screens/admin/png/StateAdminNoAccess.png` |  |
| Session ended | 1 | `design/screens/admin/source/StateAdminSessionEnded.dc.html` | `design/screens/admin/png/StateAdminSessionEnded.png` |  |
| 1b · First sign-in: new password and authenticator | 1 | `design/screens/admin/source/FirstSignIn.dc.html` | `design/screens/admin/png/FirstSignIn.png` |  |
