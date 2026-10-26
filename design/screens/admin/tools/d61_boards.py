"""Writes the D61 configuration boards (admin screens 32 to 37 and two dialogs), EN and FR.

The copy comes from packages/i18n so the boards and the portal say the same words. The markup
follows the other admin boards: the shared Sidebar, cards with rows, outline and primary buttons,
pills in the design system's tones. Run from the repository root:

    python design/screens/admin/tools/d61_boards.py
"""
import html
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
OUT = ROOT / "design/screens/admin/source"
I18N = {lang: json.loads((ROOT / f"packages/i18n/locales/{lang}.json").read_text(encoding="utf-8")) for lang in ("en", "fr")}


def t(lang, key, **args):
    node = I18N[lang]
    for part in key.split("."):
        node = node[part]
    for k, v in args.items():
        node = node.replace("{{" + k + "}}", str(v))
    return html.escape(node)


# --- Pieces of the design system, as the other admin boards write them ---------------------------

PILL = {
    "brand": "background: #E3EEEA; color: #143F37",
    "warning": "background: #FDF3E4; color: #5A4318",
    "neutral": "background: #EEF1EC; color: #3B4842",
    "danger": "background: #FBE9E7; color: #8C1D18",
}


def pill(text, tone="neutral"):
    return (f'<span style="display: inline-flex; align-items: center; height: 26px; padding: 0 10px; '
            f'border-radius: 999px; {PILL[tone]}; font-size: 12.5px; font-weight: 700; white-space: nowrap">{text}</span>')


def button(text, primary=False):
    style = ("border: 0; background: #1E5A4E; color: #FFFFFF" if primary
             else "border: 1px solid #7F8B84; background: #FFFFFF; color: #14201C")
    return (f'<button type="button" style="height: 36px; padding: 0 12px; border-radius: 10px; {style}; '
            f'font: 600 13.5px Figtree, sans-serif; white-space: nowrap">{text}</button>')


def meta(text):
    return f'<span style="font-size: 13px; color: #4F5D57">{text}</span>'


def row(main, *right):
    return ('<div style="display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; '
            'gap: 12px; min-height: 56px; padding: 8px 0; border-top: 1px solid #EEF1EC">'
            f'<div style="display: flex; flex-direction: column; gap: 2px; min-width: 0; flex: 1 1 320px">{main}</div>'
            f'<div style="display: flex; gap: 8px; align-items: center">{"".join(right)}</div></div>')


def card(title, body, action=""):
    head = (f'<div style="display: flex; justify-content: space-between; align-items: center; gap: 12px">'
            f'<h2 style="margin: 0; padding: 10px 0; font-size: 16px; font-weight: 700">{title}</h2>{action}</div>')
    return ('<section style="display: flex; flex-direction: column; padding: 8px 18px 10px; border-radius: 16px; '
            f'background: #FFFFFF; border: 1px solid #DDE3DC">{head}{body}</section>')


def strong(text):
    return f'<span style="font-weight: 700">{text}</span>'


def field(label, value, hint=""):
    out = (f'<label style="display: flex; flex-direction: column; gap: 6px"><span style="font-size: 13.5px; font-weight: 600">{label}</span>'
           f'<span style="display: flex; align-items: center; min-height: 44px; padding: 0 12px; border: 1px solid #7F8B84; '
           f'border-radius: 10px; background: #FFFFFF; font-size: 14px">{value}</span></label>')
    if hint:
        out += meta(hint)
    return out


def check(label, on):
    box = "&#10003;" if on else ""
    bg = "#1E5A4E" if on else "#FFFFFF"
    return (f'<label style="display: flex; gap: 10px; align-items: center; min-height: 44px; font-size: 14px">'
            f'<span aria-hidden="true" style="width: 22px; height: 22px; border-radius: 6px; border: 1px solid #1E5A4E; '
            f'background: {bg}; color: #FFFFFF; display: inline-flex; align-items: center; justify-content: center; font-size: 14px">{box}</span>{label}</label>')


def page(lang, title, active, main, back=None):
    back_link = f'<a href="#" style="font-size: 13.5px; font-weight: 600">{back}</a>' if back else ""
    sidebar = "Sidebar_fr" if lang == "fr" else "Sidebar"
    return f"""<!doctype html>
<html lang="{lang}">
<head>
<meta charset="utf-8">
<title>{title}</title>
<script src="./support.js"></script>
</head>
<body>
<x-dc>
<helmet>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Bricolage+Grotesque:opsz,wght@12..96,500;12..96,600;12..96,700&amp;family=Figtree:wght@400;500;600;700&amp;display=swap">
<style>
body{{margin:0}}
a{{color:#1E5A4E}}a:hover{{color:#143F37}}
</style>
</helmet>
<div style="min-height: 100vh; box-sizing: border-box; background: #F4F6F2; color: #14201C; font-family: 'Figtree', system-ui, sans-serif; display: flex; flex-wrap: wrap">
<div style="flex: 1 1 248px; background: #FFFFFF; border-right: 1px solid #DDE3DC"><dc-import name="{sidebar}" active="{active}" hint-size="248px,960px"></dc-import></div>
<main style="flex: 999 1 560px; min-width: 0; padding: 28px 32px 40px; display: flex; flex-direction: column; gap: 18px">
{main}
</main>
</div>
</x-dc>
<script type="text/x-dc" data-dc-script data-props='{{"$preview":{{"width":1440,"height":1000}}}}'>
class Component extends DCLogic {{
  renderVals() {{ return {{}}; }}
}}
</script>
</body>
</html>
""".replace("{back_link}", back_link)


def heading(lang, back, title, subtitle):
    return (f'<div style="display: flex; flex-direction: column; gap: 4px"><a href="#" style="font-size: 13.5px; font-weight: 600">{back}</a>'
            f'<h1 style="margin: 0; font-family: \'Bricolage Grotesque\', sans-serif; font-weight: 700; font-size: 30px; letter-spacing: -0.4px">{title}</h1>'
            f'<span style="font-size: 14px; color: #4F5D57; max-width: 72ch">{subtitle}</span></div>')


def dialog(title, body, actions):
    return ('<div style="position: fixed; inset: 0; background: rgba(20, 32, 28, 0.32); display: flex; align-items: center; justify-content: center">'
            '<div role="dialog" aria-modal="true" style="width: 520px; max-width: calc(100% - 32px); display: flex; flex-direction: column; gap: 14px; '
            'padding: 22px 24px; border-radius: 16px; background: #FFFFFF; border: 1px solid #DDE3DC">'
            f'<h2 style="margin: 0; font-size: 18px; font-weight: 700">{title}</h2>{body}'
            f'<div style="display: flex; justify-content: flex-end; gap: 8px">{actions}</div></div></div>')


# --- Boards -------------------------------------------------------------------------------------------

def providers(lang, with_dialog=False):
    p = "admin.providers."
    rows = (
        row(strong("Notch Pay · notchpay")
            + meta(f'{t(lang, p + "adapter.NOTCHPAY")} · {t(lang, p + "mode.LIVE")} · {t(lang, p + "keysFound")} · {t(lang, p + "circuit.CLOSED")}')
            + meta(t(lang, p + "holds", n=3, amount="45 000 XAF") + " · /v1/webhooks/payments/notchpay"),
            pill(t(lang, p + "status.ACTIVE"), "brand"), button(t(lang, p + "test")), button(t(lang, "admin.settings.change")))
        + row(strong("Notch Pay 2 · notchpay-2")
              + meta(f'{t(lang, p + "adapter.NOTCHPAY")} · {t(lang, p + "mode.LIVE")} · {t(lang, p + "keysFound")}')
              + meta(t(lang, p + "holds", n=0, amount="0 XAF") + " · /v1/webhooks/payments/notchpay-2"),
              pill(t(lang, p + "status.DRAFT"), "neutral"), button(t(lang, p + "test")), button(t(lang, "admin.settings.change")))
        + row(strong("Sandbox · sandbox")
              + meta(f'{t(lang, p + "adapter.SANDBOX")} · {t(lang, p + "mode.TEST")} · {t(lang, p + "keysFound")}')
              + meta(t(lang, p + "holds", n=0, amount="0 XAF") + " · /v1/webhooks/payments/sandbox"),
              pill(t(lang, p + "status.DISABLED"), "neutral"), button(t(lang, p + "test")), button(t(lang, "admin.settings.change")))
    )
    momo = t(lang, "admin.payments.methods.MTN_MOMO")
    orange = t(lang, "admin.payments.methods.ORANGE_MONEY")
    routes = meta(t(lang, p + "routesHelp"))
    for purpose in ("COLLECT", "REFUND", "PAYOUT"):
        for method, chain in ((momo, "notchpay"), (orange, "notchpay")):
            routes += row(strong(f'{t(lang, p + "purpose." + purpose)} · {method}'), f'<span style="font-weight: 700">{chain}</span>')
    main = (heading(lang, t(lang, p + "back"), t(lang, p + "title"), t(lang, p + "subtitle"))
            + card(t(lang, p + "list"), rows, button(t(lang, p + "add")))
            + card(t(lang, p + "routes"), routes, button(t(lang, "admin.settings.change"))))
    if with_dialog:
        body = (field(t(lang, p + "name"), "Notch Pay 2")
                + field(t(lang, p + "modeLabel"), t(lang, p + "mode.LIVE"))
                + field(t(lang, p + "statusLabel"), t(lang, p + "status.ACTIVE"))
                + field(t(lang, p + "secretRef"), "notchpay-2")
                + '<fieldset style="display: grid; gap: 4px; border: 1px solid #DDE3DC; border-radius: 12px; padding: 10px 14px; margin: 0">'
                + f'<legend style="font-size: 13.5px; font-weight: 600; padding: 0 4px">{t(lang, p + "checks")}</legend>'
                + check(t(lang, p + "check.recordsOfProcessing"), True)
                + check(t(lang, p + "check.threatModel"), True)
                + check(t(lang, p + "check.contract"), True)
                + check(t(lang, p + "check.testTransactions"), False)
                + "</fieldset>"
                + field(t(lang, "admin.bookings.reasonRequired"), "")
                + f'<p role="alert" style="margin: 0; font-size: 13px; color: #8C1D18">{html.escape(error(lang, "payment-provider.checks-missing"))}</p>')
        main += dialog(t(lang, p + "change"), body,
                       button(t(lang, "admin.common.cancel")) + button(t(lang, "admin.common.save"), True))
    return page(lang, t(lang, p + "title"), "payments", main)


def error(lang, code):
    path = ROOT / "backend/services/core/src/main/resources" / ("messages_fr.properties" if lang == "fr" else "messages.properties")
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith(f"error.{code}.title="):
            return line.split("=", 1)[1]
    raise KeyError(code)


def senders(lang):
    p = "admin.senders."
    rows = (
        row(strong("SMS gateway · sms-main")
            + meta(f'{t(lang, p + "channel.SMS")} · {t(lang, p + "adapter.HTTP_SMS")} · {t(lang, p + "priority", n=1)} · {t(lang, "admin.providers.keysFound")}'),
            pill(t(lang, p + "status.ACTIVE"), "brand"), button(t(lang, p + "test")), button(t(lang, "admin.settings.change")))
        + row(strong("SMS backup · sms-backup")
              + meta(f'{t(lang, p + "channel.SMS")} · {t(lang, p + "adapter.HTTP_SMS")} · {t(lang, p + "priority", n=2)} · {t(lang, "admin.providers.keysMissing")}'),
              pill(t(lang, p + "status.DRAFT"), "neutral"), button(t(lang, p + "test")), button(t(lang, "admin.settings.change")))
        + row(strong("Mail · mail-main")
              + meta(f'{t(lang, p + "channel.EMAIL")} · {t(lang, p + "adapter.SMTP")} · {t(lang, p + "priority", n=1)} · {t(lang, "admin.providers.keysFound")}'),
              pill(t(lang, p + "status.ACTIVE"), "brand"), button(t(lang, p + "test")), button(t(lang, "admin.settings.change")))
    )
    main = (heading(lang, t(lang, p + "back"), t(lang, p + "title"), t(lang, p + "subtitle"))
            + card(t(lang, p + "list"), rows, button(t(lang, p + "add"))))
    return page(lang, t(lang, p + "title"), "settings", main)


def jobs(lang, with_dialog=False):
    p = "admin.jobs."
    tabs = ('<div role="tablist" style="display: flex; gap: 6px; background: #FFFFFF; border: 1px solid #DDE3DC; border-radius: 12px; padding: 4px; align-self: flex-start">'
            + "".join(
                f'<button type="button" role="tab" aria-selected="{str(i == 0).lower()}" style="height: 36px; padding: 0 14px; border: 0; border-radius: 9px; '
                + ("background: #1E5A4E; color: #FFFFFF" if i == 0 else "background: transparent; color: #2C3833")
                + f'; font: 600 14px Figtree, sans-serif">{t(lang, p + "service." + s)}</button>'
                for i, s in enumerate(("core", "tracking", "ai")))
            + "</div>")
    last_ok = t(lang, p + "last", when="26 Oct 2026, 14:32" if lang == "en" else "26 oct. 2026, 14:32", outcome="OK")

    def job(name, sched, status, tone, caution=False, pending=False):
        body = strong(t(lang, p + "name." + name.replace(".", "_"))) + meta(f"{name} · {sched} · {last_ok}")
        if pending:
            body += f'<span style="font-size: 13px; font-weight: 700; color: #5A4318">{t(lang, p + "pending")}</span>'
        if caution:
            body += meta(t(lang, p + "caution"))
        return row(body, pill(t(lang, p + "status." + status), tone), button(t(lang, "admin.settings.change")))

    every = lambda i: t(lang, p + "every", interval=i)
    rows = (job("core.crisis.escalate", every("PT30S"), "ACTIVE", "brand", caution=True)
            + job("core.payments.poll", every("PT20S"), "ACTIVE", "brand", caution=True)
            + job("core.messaging.licence-renewals", "0 0 9 * * * (Africa/Douala)", "PAUSED", "warning", pending=True)
            + job("core.payments.reconcile", "0 0 3 * * * (Africa/Douala)", "ACTIVE", "brand"))
    main = (heading(lang, t(lang, "admin.senders.back"), t(lang, p + "title"), t(lang, p + "subtitle"))
            + tabs + card(t(lang, p + "service.core"), rows))
    if with_dialog:
        body = (f'<p style="margin: 0; padding: 10px 12px; border-radius: 10px; background: #FDF3E4; color: #5A4318; font-size: 13.5px; font-weight: 600">{t(lang, p + "cautionLong")}</p>'
                + field(t(lang, "admin.providers.statusLabel"), t(lang, p + "status.PAUSED"))
                + field(t(lang, p + "interval"), "PT30S")
                + meta(t(lang, p + "restart"))
                + field(t(lang, "admin.bookings.reasonRequired"), ""))
        main += dialog(t(lang, p + "name.core_crisis_escalate"), body, button(t(lang, "admin.common.cancel")) + button(t(lang, "admin.common.save"), True))
    return page(lang, t(lang, p + "title"), "settings", main)


def copy(lang):
    p = "admin.copy."
    edited = t(lang, "admin.settings.changed", version=2, name="C. Editor", when="25 Oct 2026, 10:12" if lang == "en" else "25 oct. 2026, 10:12")
    push = (row(meta("push.booking-confirmed") + '<span lang="en">Appointment booked: {0} at {1}.</span>'
                + '<span lang="fr">Rendez-vous réservé : {0} à {1}.</span>', button(t(lang, "admin.settings.change")))
            + row(meta(f"push.rate-session · {edited}") + '<span lang="en">How did it go? You can rate it now.</span>'
                  + '<span lang="fr">Comment ça s’est passé ? Notez-la.</span>', button(t(lang, "admin.settings.change"))))
    sms = row(meta("sms.rescheduled") + '<span lang="en">Appointment moved to {0} at {1}.</span>'
              + '<span lang="fr">Rendez-vous déplacé au {0} à {1}.</span>', button(t(lang, "admin.settings.change")))
    staff = row(meta(f'category.ACUTE_ANXIETY · {t(lang, p + "forStaff")}') + '<span lang="en">acute anxiety</span>'
                + '<span lang="fr">anxiété aigue</span>', button(t(lang, "admin.settings.change")))
    main = (heading(lang, t(lang, p + "back"), t(lang, p + "title"), t(lang, p + "subtitle"))
            + card(t(lang, p + "channel.push"), push)
            + card(t(lang, p + "channel.sms"), sms)
            + card(t(lang, p + "channel.category"), staff))
    return page(lang, t(lang, p + "title"), "content", main)


def models(lang):
    p = "admin.models."
    mrows = (row(strong("local-a · llama-3.1-8b") + meta(f'{t(lang, p + "kind.CHAT")} · {t(lang, p + "local")} · {t(lang, "admin.providers.keysFound")}'),
                 pill(t(lang, "admin.senders.status.ACTIVE"), "brand"), button(t(lang, "admin.settings.change")))
             + row(strong("whisper-local · whisper-large-v3") + meta(f'{t(lang, p + "kind.SPEECH")} · {t(lang, p + "local")} · {t(lang, "admin.providers.keysFound")}'),
                   pill(t(lang, "admin.senders.status.ACTIVE"), "brand"), button(t(lang, "admin.settings.change")))
             + row(strong("vendor-b · large-1") + meta(f'{t(lang, p + "kind.CHAT")} · {t(lang, p + "remote")} · {t(lang, "admin.providers.keysMissing")}'),
                   pill(t(lang, "admin.senders.status.DRAFT"), "neutral"), button(t(lang, "admin.settings.change"))))
    live = t(lang, p + "live")
    budget = t(lang, p + "budget")
    rrows = (row(strong(t(lang, "admin.ai.feature.TRANSLATION")) + meta(f"{live}: local-a · {budget}: 250 000")
                 + f'<span style="font-size: 13px; font-weight: 700">{t(lang, p + "pending")}: local-a → vendor-b · S. Admin · {t(lang, p + "notPassed")}</span>',
                 button(t(lang, "admin.settings.change")), button(t(lang, p + "evaluate")), button(t(lang, p + "promote"), True), button(t(lang, p + "discard")))
             + row(strong(t(lang, "admin.ai.feature.TRACKING_INSIGHTS")) + meta(f'{live}: local-a · {budget}: {t(lang, p + "defaultBudget")}'),
                   button(t(lang, "admin.settings.change")), button(budget))
             + row(strong(t(lang, "admin.ai.feature.SESSION_SCRIBE")) + meta(f'{live}: {t(lang, "admin.settings.none")} · {budget}: {t(lang, p + "defaultBudget")}'),
                   button(t(lang, "admin.settings.change")), button(budget)))
    main = (heading(lang, t(lang, p + "back"), t(lang, p + "title"), t(lang, p + "subtitle"))
            + card(t(lang, p + "list"), mrows, button(t(lang, p + "add")))
            + card(t(lang, p + "routes"), rrows))
    return page(lang, t(lang, p + "title"), "ai", main)


def lists(lang):
    p = "admin.lists."
    name = lambda code: strong(t(lang, p + "name." + code.replace(".", "_")))
    all_rows = (row(name("ai.safety.phrases") + meta(f'ai.safety.phrases · {t(lang, p + "live", version=2)} · {t(lang, p + "drafts", n=1)}'), button(t(lang, p + "open")))
                + row(name("ai.guardrails.clinical") + meta(f'ai.guardrails.clinical · {t(lang, p + "fromCode")}'), button(t(lang, p + "open")))
                + row(name("ai.eval.safety-monitor") + meta(f'ai.eval.safety-monitor · {t(lang, p + "live", version=1)}'), button(t(lang, p + "open")))
                + row(strong(t(lang, p + "evalFor", feature=t(lang, "admin.ai.feature.TRANSLATION"))) + meta(f'ai.eval.translation · {t(lang, p + "fromCode")}'), button(t(lang, p + "open")))
                + row(name("ai.companion.corpus") + meta(f'ai.companion.corpus · {t(lang, p + "fromCode")}'), button(t(lang, p + "open"))))
    entries = ('<pre style="margin: 0; max-height: 220px; overflow: auto; padding: 12px; border: 1px solid #DDE3DC; border-radius: 10px; '
               'font: 13px ui-monospace, Menlo, monospace; white-space: pre-wrap">{\n  "SUICIDAL_IDEATION": ["kill myself", "end my life", "mettre fin a mes jours", …],\n  "SELF_HARM": ["cut myself", "me faire du mal", …]\n}</pre>')
    when = "26 Oct 2026, 11:05" if lang == "en" else "26 oct. 2026, 11:05"
    versions = (row(strong(f'v3 · {t(lang, p + "status.DRAFT")}') + meta(f"More phrases for self-harm · Dr. Ade · {when}"),
                    button(t(lang, p + "check")), button(t(lang, p + "signOff"), True), button(t(lang, p + "decline")))
                + row(strong(f'v2 · {t(lang, p + "status.LIVE")}') + meta(f'Clinicians · Dr. Ade · {when} · {t(lang, p + "decidedBy", name="Dr. Bih")} · recall EN 100.0, FR 99.4'))
                + row(strong(f'v1 · {t(lang, p + "status.RETIRED")}') + meta(f'First version · Dr. Ade · {when} · {t(lang, p + "decidedBy", name="Dr. Bih")} · recall EN 99.2, FR 99.0')))
    detail = (f'<h3 style="margin: 10px 0 6px; font-size: 14px">{t(lang, p + "inForce")}</h3>{entries}'
              f'<h3 style="margin: 14px 0 6px; font-size: 14px">{t(lang, p + "versions")}</h3>{versions}')
    main = (heading(lang, t(lang, p + "back"), t(lang, p + "title.ai"), t(lang, p + "subtitle"))
            + card(t(lang, p + "all"), all_rows)
            + card(t(lang, p + "name.ai_safety_phrases"), detail, button(t(lang, p + "draft"))))
    return page(lang, t(lang, p + "title.ai"), "ai", main)


BOARDS = {
    "PaymentProviders": lambda lang: providers(lang),
    "PaymentProviderActivate": lambda lang: providers(lang, with_dialog=True),
    "MessageSenders": senders,
    "Jobs": lambda lang: jobs(lang),
    "JobPause": lambda lang: jobs(lang, with_dialog=True),
    "NotificationCopy": copy,
    "AIModels": models,
    "ReviewedLists": lists,
}

if __name__ == "__main__":
    for name, build in BOARDS.items():
        for lang in ("en", "fr"):
            file = OUT / (f"{name}_fr.dc.html" if lang == "fr" else f"{name}.dc.html")
            file.write_text(build(lang), encoding="utf-8", newline="\n")
            print("wrote", file.relative_to(ROOT))
