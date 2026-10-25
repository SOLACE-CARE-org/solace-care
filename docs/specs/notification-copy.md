# Solace — Notification Copy

Oct 16, 2026 · @Banseka

This is every message Solace sends, in English and French. None of them says anything about a person's health, and every SMS fits in one standard message.

> Since D61 (25 Oct 2026), Super admins and Content editors can edit this copy on the portal, in both languages, with the rules below checked on save. The wording here is the approved first version, and the portal shows it beside any edit.

## Rules

- **Neutral wording:** no message names a condition, a module, a mood, a specialty or the words therapy or mental health. "Appointment" and "specialist" are the most a lock screen ever shows.
- **Discreet mode:** when a seeker turns on discreet notifications in Settings, every push becomes "Solace: you have a reminder" (FR: "Solace : vous avez un rappel"). Details appear only inside the app.
- **Language:** each message goes in the recipient's app language, so one booking can notify the seeker in French and the specialist in English.
- **Channels:** account holders get push. Guests get SMS, because they have no app account. Staff get push plus SMS for crisis alerts only.
- **SMS length:** at most 160 characters after placeholders are filled. French SMS avoid ê, â, î, ô, û, ç, ë and ï, because one of them switches the whole SMS to a 70-character format and can double its cost. é, è, à and ù are safe.
- **Placeholders:** {code}, {date}, {time}, {link}, {amount}, {method}, {category}. {link} is a short link of about 25 characters. Dates and times use the recipient's time zone.
- **Limits:** at most one tracking push a day, never by SMS, stopping after 5 ignored days, as the Tracking Service Spec sets out.

## Seekers and guests

| Event | Channel | English | French |
| --- | --- | --- | --- |
| Sign-in or sign-up code | SMS | Solace: your code is {code}. It expires in \[10\] min. Never share it with anyone. | Solace : votre code est {code}. Il expire dans \[10\] min. Ne le donnez à personne. |
| Booking confirmed | Push | Appointment booked: {date} at {time}. | Rendez-vous réservé : {date} à {time}. |
| Booking confirmed | SMS, guests | Solace: your appointment on {date} at {time} is confirmed. Details and join link: {link} | Solace : votre rendez-vous du {date} à {time} est confirmé. Détails et lien : {link} |
| Day before | Push | Reminder: appointment tomorrow at {time}. | Rappel : rendez-vous demain à {time}. |
| Joining opens, 10 min before | Push | Your appointment starts at {time}. You can join now. | Votre rendez-vous commence à {time}. Vous pouvez le rejoindre. |
| Joining opens, 10 min before | SMS, guests | Solace: your appointment starts at {time}. Join here: {link} | Solace : votre rendez-vous commence à {time}. Rejoindre ici : {link} |
| Payment received | Push | Payment received: {amount} FCFA. Your receipt is in the app. | Paiement reçu : {amount} FCFA. Votre reçu est dans l'application. |
| Payment not completed | Push | Payment not completed. Your time is held until {time}. | Paiement non abouti. Votre créneau est gardé jusqu'à {time}. |
| Payment not completed | SMS, guests | Solace: your payment was not completed. Your time is held until {time}. Try again: {link} | Solace : votre paiement n'a pas abouti. Votre créneau est gardé jusqu'à {time}. Réessayer : {link} |
| Seeker cancelled | Push or SMS | Appointment cancelled. A refund of {amount} FCFA is on its way. | Rendez-vous annulé. Un remboursement de {amount} FCFA est en cours. |
| Specialist cancelled | Push | Your appointment on {date} was cancelled by the specialist. Full refund on its way. Pick a new time in the app. | Votre rendez-vous du {date} a été annulé par le spécialiste. Remboursement total en cours. Choisissez un autre horaire dans l'application. |
| Specialist cancelled | SMS, guests | Solace: your appointment on {date} was cancelled by the specialist. Full refund on its way. New time: {link} | Solace : votre rendez-vous du {date} a été annulé par le spécialiste. Remboursement total en cours. Nouvel horaire : {link} |
| Rescheduled | Push or SMS | Appointment moved to {date} at {time}. | Rendez-vous déplacé au {date} à {time}. |
| Refund sent | Push or SMS | Solace: a refund of {amount} FCFA has been sent to your {method} account. | Solace : un remboursement de {amount} FCFA a été envoyé sur votre compte {method}. |
| After the appointment | Push | How was your appointment? Tell us in one tap. | Comment s'est passé votre rendez-vous ? Répondez en un geste. |
| Task from the specialist | Push | You have a new task from your specialist. | Votre spécialiste vous a confié une nouvelle tâche. |
| Daily tracking reminder | Push only | Time to log today. | C'est l'heure de noter votre journée. |
| Pattern or invite from tracking | Push only | You have something new to look at in Solace. | Vous avez du nouveau à consulter dans Solace. |
| Data copy ready | Push and email | Your copy of your data is ready. It can be downloaded for \[7\] days. | Votre copie de données est prête. Elle reste téléchargeable \[7\] jours. |

An invite from tracking uses that day's one tracking push, in place of the daily reminder.

## Specialists

Specialist pushes always name the session language, so nobody joins expecting the wrong one.

| Event | Channel | English | French |
| --- | --- | --- | --- |
| Application received | Push and email | Application received. We'll tell you at each step. | Candidature reçue. Nous vous informerons à chaque étape. |
| Change requested | Push | Your application needs a change. Open Solace to see what. | Votre candidature doit être complétée. Ouvrez Solace pour voir quoi. |
| Interview booked | Push | Onboarding interview booked: {date} at {time}. | Entretien d'intégration réservé : {date} à {time}. |
| Approved | Push and email | You're approved. Seekers can now find and book you. | Votre candidature est approuvée. Les usagers peuvent désormais vous trouver et vous réserver. |
| Decision made | Push and email | There's an update on your application. Open Solace to read it. | Votre candidature a évolué. Ouvrez Solace pour en savoir plus. |
| New booking | Push | New session: {date} at {time} · {modality} · {language}. | Nouvelle séance : {date} à {time} · {modality} · {language}. |
| Seeker cancelled | Push | The session on {date} at {time} was cancelled by the seeker. | La séance du {date} à {time} a été annulée par l'usager. |
| 10 min before | Push | Your {language} session at {time} starts in 10 minutes. | Votre séance en {language} de {time} commence dans 10 minutes. |
| Seeker waiting | Push | Your seeker is in the waiting room. | Votre usager est dans la salle d'attente. |
| Brief ready (Phase 3) | Push | Your brief for the {time} session is ready. | La fiche de votre séance de {time} est prête. |
| Note draft ready (Phase 4) | Push | A note draft is waiting for your review. | Un brouillon de note attend votre relecture. |
| Payout sent | Push | Payout sent: {amount} FCFA to {method}. | Versement envoyé : {amount} FCFA sur {method}. |
| Payout failed | Push and email | Your payout couldn't be sent. Check your payout details in Solace. | Votre versement n'a pas pu être envoyé. Vérifiez vos coordonnées de versement dans Solace. |
| New review | Push | You have a new review. | Vous avez un nouvel avis. |
| Licence renewal, 60 days before | Push and email | Your licence expires on {date}. Upload the renewal to stay bookable. | Votre autorisation d'exercer expire le {date}. Envoyez son renouvellement pour rester réservable. |

The app shows {modality} and {language} in the specialist's own language: "audio" and "French" for an English-speaking specialist, "audio" and "français" for a French-speaking one.

## Staff

Staff messages carry no personal details; the responder opens the portal to see who and what.

| Event | Channel | English | French |
| --- | --- | --- | --- |
| Crisis alert | Push and SMS to the on-call responder | Solace crisis alert: {category}, raised {time}. Open the crisis queue now. | Alerte de crise Solace : {category}, levée à {time}. Ouvrez la file de crise maintenant. |
| Not acknowledged in 5 min | Push and SMS to the backup | Solace: a crisis alert has waited 5 min. You are the backup. Open the crisis queue now. | Solace : une alerte de crise attend depuis 5 min. Vous assurez le relais. Ouvrez la file de crise. |
| Still not acknowledged | Push and SMS to Super admins | Solace: a crisis alert is still not acknowledged after 5 min. Open the crisis queue now. | Solace : une alerte de crise n'est toujours pas prise en charge après 5 min. Ouvrez la file de crise. |
| Refund needs approval | Push | {n} refunds are waiting for your approval. | {n} remboursements attendent votre approbation. |
| Payout run needs approval | Push | A payout run is ready for a second approver. | Un lot de versements attend un second approbateur. |
| Module submitted | Push to clinical reviewers | {module} {version} is waiting for clinical review. | {module} {version} attend une revue clinique. |
| Data requests due | Daily email to Operations | {n} data requests are due this week. | {n} demandes de données arrivent à échéance cette semaine. |
| Sender test (D61) | SMS or email to the number or address a Super admin gives | Solace: test message from the admin portal. This SMS sender works. (Email: "This email sender works.") | Solace : message de test depuis le portail. Cet expediteur SMS fonctionne. (E-mail : « Cet expéditeur d'e-mails fonctionne. ») |

{category} is one of the six safety-monitor categories, in the staff member's language: suicidal ideation, self-harm, acute anxiety, violent thoughts, substance crisis, risk-taking. In French SMS, "anxiété aiguë" is sent as "anxiété aigue" to stay in the standard format.

## Emails

Email is used only for records people may want to keep. Each shows its subject, then its first line.

| Email | English | French |
| --- | --- | --- |
| Receipt | **Your Solace receipt, {date}.** Thank you for your payment of {amount} FCFA. | **Votre reçu Solace du {date}.** Merci pour votre paiement de {amount} FCFA. |
| Data copy ready | **Your Solace data is ready.** Download it from the app within \[7\] days. | **Vos données Solace sont prêtes.** Téléchargez-les depuis l'application dans les \[7\] jours. |
| Account deleted | **Your Solace account has been deleted.** We have removed your account and your data, except records the law requires us to keep. | **Votre compte Solace a été supprimé.** Nous avons effacé votre compte et vos données, sauf les éléments que la loi nous oblige à conserver. |
| Application received | **We've received your application.** We check every specialist before seekers can book them. | **Nous avons bien reçu votre candidature.** Nous vérifions chaque spécialiste avant qu'il puisse être réservé. |
| Application decision | **An update on your Solace application.** Open the app to read the decision and the reason. | **Votre candidature Solace a évolué.** Ouvrez l'application pour lire la décision et son motif. |
| Payout statement | **Your Solace payout, {period}.** {amount} FCFA was sent to {method}. | **Votre versement Solace, {period}.** {amount} FCFA ont été envoyés sur {method}. |
| Staff account created | **Your Solace Admin account is ready.** Sign in within 72 hours with the temporary password your Super admin gives you in person. You will then choose your own password and add an authenticator app. This email never contains a password. | **Votre compte Solace Admin est prêt.** Connectez-vous sous 72 heures avec le mot de passe temporaire remis en main propre par votre Super admin. Vous choisirez ensuite votre mot de passe et ajouterez une application d'authentification. Cet e-mail ne contient jamais de mot de passe. |

No email ever contains session details, notes, tracking data or a specialist's specialty.

## Open questions

- [x] **SMS sender ID:** register "Solace" as the sender name with MTN and Orange, or send from a short number?
- [x] **Code lifetime:** confirm the \[10\]-minute code expiry and the \[7\]-day download window.
- [x] **WhatsApp:** the System Design lists it as a channel; do guests get WhatsApp instead of SMS where they have it?
- [x] **Crisis follow-up:** should Solace ever message a person after a crisis alert, or only call? Today the copy has no automated message for this.
