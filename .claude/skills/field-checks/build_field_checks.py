# -*- coding: utf-8 -*-
"""Build the owner's phone-sized field-check PDF: one item per page, an AcroForm checkbox per check.

Usage: python build_field_checks.py <items.json> <out.pdf>

items.json is a list of objects: {tag, ref, title, intro, checks: [..], tier}. See SKILL.md.
Text fields take reportlab's Paragraph mini-markup (<b>, <i>, <br/>).

Every page carries an ID (F7 = PDF page 7) and every check a number within it (F7.3), and every
page's footer says how to reply. No text anywhere is set below MIN_PT; a page that would need
smaller text, or a check that breaks the SKILL's ban list, fails the build (exit 1).
"""
import json
import re
import sys

from reportlab.lib import colors
from reportlab.lib.pagesizes import mm
from reportlab.lib.styles import ParagraphStyle
from reportlab.pdfgen import canvas
from reportlab.platypus import Paragraph

W, H = 100 * mm, 178 * mm
M = 7 * mm
BOX = 5.5 * mm
GAP = 2.5 * mm
ABOX = 4.8 * mm   # the App page's box
AGAP = 1.2 * mm
BOTTOM = 15 * mm  # room for the two footer lines

# Hard legibility floor, in points, for every piece of text in the PDF (SKILL §3).
MIN_PT = 10.0

TAGCOL = {
    'Open issue': colors.HexColor('#38761d'),
}

# Tiers that never get a page: questions are asked in chat, and Deferred items (they need
# hardware the owner does not have) are listed in chat as one line each (SKILL §2).
CHAT_ONLY_TIERS = ('Answer', 'Deferred')

# SKILL §2 "Never put in the PDF", enforced on the check text. A match fails the build.
BANNED = [
    (re.compile(r'stop ?watch|timer app|time it (yourself|by hand)', re.I),
     'stopwatch check: the app records time; Claude reads it from the export'),
    (re.compile(r'(second|another|other) (phone|device|tablet)|other android version', re.I),
     'second-device check: the owner has one phone'),
    (re.compile(r'\?\s*(</?\w+>\s*)*$'),
     'question hidden in a check: ask it in chat'),
    (re.compile(r'\bI (verify|check|confirm)\b|\bI\'ll (verify|check)\b', re.I),
     'a check Claude does itself: say it once in the chat message instead'),
]


def tag_colour(tag):
    if tag in TAGCOL:
        return TAGCOL[tag]
    if tag.lower().endswith('question'):
        return colors.HexColor('#7a4b00')
    return colors.HexColor('#0b5394')  # a release version


STYLES = {
    'tag': ParagraphStyle('tag', fontName='Helvetica-Bold', fontSize=MIN_PT, leading=12, textColor=colors.white),
    'ref': ParagraphStyle('ref', fontName='Helvetica', fontSize=MIN_PT, leading=12, textColor=colors.white, alignment=2),
    'title': ParagraphStyle('title', fontName='Helvetica-Bold', fontSize=15, leading=18),
    'idx': ParagraphStyle('idx', fontName='Helvetica', fontSize=MIN_PT, leading=MIN_PT * 1.28),
    'cover': ParagraphStyle('cover', fontName='Helvetica-Bold', fontSize=18, leading=22),
}


def body(sz, italic=False):
    return ParagraphStyle(
        'b%s%s' % (sz, italic),
        fontName='Helvetica-Oblique' if italic else 'Helvetica',
        fontSize=sz, leading=sz * 1.3,
        textColor=colors.HexColor('#444444') if italic else colors.black,
    )


def check_text(page, k, text):
    return '<b>F%d.%d</b>&nbsp; %s' % (page, k, text)


def layout(page, intro, checks, sz):
    """Rows at body size sz; the intro is set 1 pt smaller, so sz >= MIN_PT + 1."""
    rows, total = [], 0
    if intro:
        p = Paragraph(intro, body(sz - 1, italic=True))
        _, h = p.wrap(W - 2 * M, H)
        rows.append(('intro', p, h))
        total += h + 2.5 * mm
    for k, ch in enumerate(checks, start=1):
        p = Paragraph(check_text(page, k, ch), body(sz))
        _, h = p.wrap(W - 2 * M - BOX - GAP, H)
        h = max(h, BOX)
        rows.append(('check', p, h))
        total += h + 2.2 * mm
    return rows, total


def app_font_size(app_items):
    """The largest size from 12 pt down to MIN_PT at which the App checklist fits one page.

    At MIN_PT the page continues onto further pages rather than shrinking below the floor.
    """
    sz = 12.0
    while sz > MIN_PT and app_pages_needed(app_items, sz) > 1:
        sz -= 0.25
    return max(sz, MIN_PT)


def app_pages_needed(app_items, sz=None):
    """Dry run of the App page layout: how many pages the app-only checklist takes."""
    if not app_items:
        return 0
    if sz is None:
        sz = app_font_size(app_items)
    pages, y = 1, H - 15 * mm - 18 - 2 * mm
    for it in app_items:
        head = Paragraph('<b>%s</b> (%s)' % (it['title'], it['ref']), body(sz))
        _, hh = head.wrap(W - 2 * M, H)
        need = hh + 1.5 * mm
        for ch in it['checks']:
            _, h = Paragraph(check_text(99, 99, ch), body(sz)).wrap(W - 2 * M - ABOX - GAP, H)
            need += max(h, ABOX) + AGAP
        if y - need < BOTTOM:
            pages += 1
            y = H - 15 * mm
        y -= need + 1.5 * mm
    return pages


def ban_errors(items):
    errors = []
    for it in items:
        for ch in it['checks']:
            for rx, why in BANNED:
                if rx.search(ch):
                    errors.append('%s "%s": %s\n    %s' % (it['ref'], it['title'], why, ch))
    return errors


def build(items, out, heading):
    errors = ban_errors(items)
    c = canvas.Canvas(out, pagesize=(W, H))
    c.setTitle(heading)
    c.setAuthor('BarSpeed')
    form = c.acroForm
    app_items = [it for it in items if it.get('tier') == 'App']
    items = [it for it in items if it.get('tier') != 'App']
    n_pages = 1 + app_pages_needed(app_items) + len(items)

    def footer(page, has_checks=True):
        c.setFillColor(colors.HexColor('#555555'))
        c.setFont('Helvetica', MIN_PT)
        c.drawRightString(W - M, 9.5 * mm, '%d / %d' % (page, n_pages))
        if has_checks:
            c.drawString(M, 4 * mm, 'Reply in chat: F%d.1 pass/fail - what you saw' % page)
        c.setFillColor(colors.black)

    def band(colour, left, right=None):
        c.setFillColor(colour)
        c.rect(0, H - 11 * mm, W, 11 * mm, fill=1, stroke=0)
        p = Paragraph(left, STYLES['tag'])
        p.wrapOn(c, W / 2 - M, 11 * mm)
        p.drawOn(c, M, H - 8 * mm)
        if right:
            r = Paragraph(right, STYLES['ref'])
            r.wrapOn(c, W / 2 - M, 11 * mm)
            r.drawOn(c, W / 2, H - 8 * mm)
        c.setFillColor(colors.black)

    # index page
    band(colors.HexColor('#222222'), 'BarSpeed field checks')
    y = H - 16 * mm
    p = Paragraph(heading, STYLES['cover'])
    _, h = p.wrap(W - 2 * M, 30 * mm)
    p.drawOn(c, M, y - h)
    y -= h + 2 * mm
    intro = ('Every check has an ID, F&lt;page&gt;.&lt;check&gt;. Reply in chat with the ID, pass or '
             'fail, and what you saw. The purple page needs only the app open. Then one item per page; '
             'Tier 2 ones need an extra set, so take them only when convenient.')
    p = Paragraph(intro, STYLES['idx'])
    _, h = p.wrap(W - 2 * M, 60 * mm)
    p.drawOn(c, M, y - h)
    y -= h + 3 * mm
    lines = []
    first = 2
    if app_items:
        lines.append('F%d. App-only checklist, no lifting <font color="#666666">(%d items)</font>'
                     % (first, len(app_items)))
        first += app_pages_needed(app_items)
    lines += ['F%d. %s <font color="#666666">(%s, %s)</font>' % (i, it['title'], it['ref'], it['tier'])
              for i, it in enumerate(items, start=first)]
    p = Paragraph('<br/>'.join(lines), STYLES['idx'])
    _, h = p.wrap(W - 2 * M, y - BOTTOM)
    p.drawOn(c, M, y - h)
    if h > y - BOTTOM:
        errors.append('index overflows page 1 at %.1f pt: shorten titles or split the release' % MIN_PT)
    footer(1, has_checks=False)
    c.showPage()

    nbox = 0
    page = 1

    # The App page: every check that needs only the app open, together, a checkbox each
    # (owner, 2026-09-05: "the ones that just require me to use the app without actually
    # lifting can be on a one page checklist"). Continues onto a further page on overflow
    # rather than shrinking below MIN_PT.
    if app_items:
        page += 1
        k = 0
        band(colors.HexColor('#5b3d8a'), 'F%d  App only - no lifting' % page, 'checklist')
        y = H - 15 * mm
        t = Paragraph('Phone in hand, no bar', STYLES['title'])
        _, h = t.wrap(W - 2 * M, 40 * mm)
        t.drawOn(c, M, y - h)
        y -= h + 2 * mm
        sz = app_font_size(app_items)
        print('app page font %.2f, pages %d' % (sz, app_pages_needed(app_items, sz)))
        for it in app_items:
            head = Paragraph('<b>%s</b> <font color="#666666">(%s)</font>' % (it['title'], it['ref']), body(sz))
            _, hh = head.wrap(W - 2 * M, H)
            # IDs depend on the page the item lands on, so measure with a placeholder first.
            need = hh + 1.5 * mm
            for ch in it['checks']:
                _, h = Paragraph(check_text(99, 99, ch), body(sz)).wrap(W - 2 * M - ABOX - GAP, H)
                need += max(h, ABOX) + AGAP
            if y - need < BOTTOM:
                footer(page)
                c.showPage()
                page += 1
                k = 0
                band(colors.HexColor('#5b3d8a'), 'F%d  App only - no lifting' % page, 'continued')
                y = H - 15 * mm
            head.drawOn(c, M, y - hh)
            y -= hh + 1.5 * mm
            for ch in it['checks']:
                k += 1
                p = Paragraph(check_text(page, k, ch), body(sz))
                _, h = p.wrap(W - 2 * M - ABOX - GAP, H)
                h = max(h, ABOX)
                nbox += 1
                form.checkbox(name='F%d_%d' % (page, k), tooltip='F%d.%d' % (page, k), x=M, y=y - ABOX,
                              size=ABOX, buttonStyle='check', borderWidth=1,
                              borderColor=colors.HexColor('#555555'),
                              fillColor=colors.white, textColor=colors.black, forceBorder=True)
                p.drawOn(c, M + ABOX + GAP, y - h)
                y -= h + AGAP
            y -= 1.5 * mm
        footer(page)
        c.showPage()

    for it in items:
        page += 1
        band(tag_colour(it['tag']), 'F%d  %s' % (page, it['tag']), '%s - %s' % (it['ref'], it['tier']))
        y = H - 15 * mm
        t = Paragraph(it['title'], STYLES['title'])
        _, h = t.wrap(W - 2 * M, 40 * mm)
        t.drawOn(c, M, y - h)
        y -= h + 3 * mm
        avail = y - BOTTOM
        # Body shrinks from 12 pt only as far as MIN_PT + 1, so the 1 pt smaller intro stays at
        # or above the floor.
        sz = 12.0
        while True:
            rows, total = layout(page, it.get('intro', ''), it['checks'], sz)
            if total <= avail or sz <= MIN_PT + 1:
                break
            sz -= 0.25
        if total > avail:
            errors.append('page F%d "%s" does not fit at the %.0f pt floor: split it into two items'
                          % (page, it['title'], MIN_PT))
        k = 0
        for kind, p, h in rows:
            if kind == 'intro':
                p.drawOn(c, M, y - h)
                y -= h + 2.5 * mm
            else:
                k += 1
                nbox += 1
                form.checkbox(name='F%d_%d' % (page, k), tooltip='F%d.%d' % (page, k), x=M, y=y - BOX,
                              size=BOX, buttonStyle='check', borderWidth=1,
                              borderColor=colors.HexColor('#555555'),
                              fillColor=colors.white, textColor=colors.black, forceBorder=True)
                p.drawOn(c, M + BOX + GAP, y - h)
                y -= h + 2.2 * mm
        print('page F%d font %.2f checks %d' % (page, sz, len(it['checks'])))
        footer(page)
        c.showPage()
    c.save()
    print('wrote %s: %d pages, %d checkboxes' % (out, n_pages, nbox))
    return errors


if __name__ == '__main__':
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    with open(sys.argv[1], encoding='utf-8') as f:
        data = json.load(f)
    items = data['items'] if isinstance(data, dict) else data
    heading = data.get('heading', 'Next session') if isinstance(data, dict) else 'Next session'
    # Questions and Deferred items go in the chat message, never on a page (owner, 2026-09-05).
    for tier, what in (('Answer', 'QUESTIONS'), ('Deferred', 'DEFERRED (one line each in chat)')):
        chat = [it for it in items if it.get('tier') == tier]
        if chat:
            print('%s, for the chat message, not the PDF:' % what)
            for q in chat:
                for ch in q['checks']:
                    print('- [%s] %s' % (q['ref'], ch))
            print()
    checks = [it for it in items if it.get('tier') not in CHAT_ONLY_TIERS]
    errs = build(checks, sys.argv[2], heading)
    if errs:
        print('FAILED: fix these and rebuild; do not send this PDF.', file=sys.stderr)
        for e in errs:
            print('- ' + e, file=sys.stderr)
        sys.exit(1)
