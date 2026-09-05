# -*- coding: utf-8 -*-
"""Build the owner's phone-sized field-check PDF: one item per page, an AcroForm checkbox per check.

Usage: python build_field_checks.py <items.json> <out.pdf>

items.json is a list of objects: {tag, ref, title, intro, checks: [..], tier}. See SKILL.md.
Text fields take reportlab's Paragraph mini-markup (<b>, <i>, <br/>).
"""
import json
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

TAGCOL = {
    'Open issue': colors.HexColor('#38761d'),
    'Deferred': colors.HexColor('#666666'),
}


def tag_colour(tag):
    if tag in TAGCOL:
        return TAGCOL[tag]
    if tag.lower().endswith('question'):
        return colors.HexColor('#7a4b00')
    return colors.HexColor('#0b5394')  # a release version


STYLES = {
    'tag': ParagraphStyle('tag', fontName='Helvetica-Bold', fontSize=9, leading=11, textColor=colors.white),
    'ref': ParagraphStyle('ref', fontName='Helvetica', fontSize=9, leading=11, textColor=colors.white, alignment=2),
    'title': ParagraphStyle('title', fontName='Helvetica-Bold', fontSize=15, leading=18),
    'idx': ParagraphStyle('idx', fontName='Helvetica', fontSize=9.5, leading=12.5),
    'cover': ParagraphStyle('cover', fontName='Helvetica-Bold', fontSize=18, leading=22),
}


def body(sz, italic=False):
    return ParagraphStyle(
        'b%s%s' % (sz, italic),
        fontName='Helvetica-Oblique' if italic else 'Helvetica',
        fontSize=sz, leading=sz * 1.3,
        textColor=colors.HexColor('#444444') if italic else colors.black,
    )


def layout(intro, checks, sz):
    rows, total = [], 0
    if intro:
        p = Paragraph(intro, body(sz - 1, italic=True))
        _, h = p.wrap(W - 2 * M, H)
        rows.append(('intro', p, h))
        total += h + 2.5 * mm
    for ch in checks:
        p = Paragraph(ch, body(sz))
        _, h = p.wrap(W - 2 * M - BOX - GAP, H)
        h = max(h, BOX)
        rows.append(('check', p, h))
        total += h + 2.2 * mm
    return rows, total


def build(items, out, heading):
    c = canvas.Canvas(out, pagesize=(W, H))
    c.setTitle(heading)
    c.setAuthor('BarSpeed')
    form = c.acroForm
    n_pages = len(items) + 1

    def footer(page):
        c.setFillColor(colors.HexColor('#888888'))
        c.setFont('Helvetica', 8)
        c.drawRightString(W - M, 4 * mm, '%d / %d' % (page, n_pages))
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
    intro = ('One item per page, a checkbox per check. Brown pages are questions you can answer by '
             'message. Blue pages are release checks that fold into a normal session. Green pages are '
             'open issues; Tier 2 ones need an extra set or an unusual mount, so take them only when '
             'convenient.')
    p = Paragraph(intro, STYLES['idx'])
    _, h = p.wrap(W - 2 * M, 60 * mm)
    p.drawOn(c, M, y - h)
    y -= h + 3 * mm
    lines = ['%d. %s <font color="#777777">(%s, %s)</font>' % (i, it['title'], it['ref'], it['tier'])
             for i, it in enumerate(items, start=2)]
    sz = 9.5
    while True:
        p = Paragraph('<br/>'.join(lines), ParagraphStyle('i2', fontName='Helvetica', fontSize=sz, leading=sz * 1.28))
        _, h = p.wrap(W - 2 * M, y - 6 * mm)
        if h <= y - 6 * mm or sz < 6:
            break
        sz -= 0.25
    p.drawOn(c, M, y - h)
    if h > y - 6 * mm:
        print('INDEX OVERFLOWS: split the list or shorten titles')
    footer(1)
    c.showPage()

    nbox = 0
    for i, it in enumerate(items, start=2):
        band(tag_colour(it['tag']), it['tag'], '%s - %s' % (it['ref'], it['tier']))
        y = H - 15 * mm
        t = Paragraph(it['title'], STYLES['title'])
        _, h = t.wrap(W - 2 * M, 40 * mm)
        t.drawOn(c, M, y - h)
        y -= h + 3 * mm
        avail = y - 7 * mm
        sz = 12.0
        while True:
            rows, total = layout(it.get('intro', ''), it['checks'], sz)
            if total <= avail or sz <= 7:
                break
            sz -= 0.25
        if total > avail:
            print('OVERFLOW page %d: %s' % (i, it['title']))
        for kind, p, h in rows:
            if kind == 'intro':
                p.drawOn(c, M, y - h)
                y -= h + 2.5 * mm
            else:
                nbox += 1
                form.checkbox(name='p%02d_c%03d' % (i, nbox), tooltip=it['title'], x=M, y=y - BOX, size=BOX,
                              buttonStyle='check', borderWidth=1, borderColor=colors.HexColor('#555555'),
                              fillColor=colors.white, textColor=colors.black, forceBorder=True)
                p.drawOn(c, M + BOX + GAP, y - h)
                y -= h + 2.2 * mm
        print('page %d font %.2f checks %d' % (i, sz, len(it['checks'])))
        footer(i)
        c.showPage()
    c.save()
    print('wrote %s: %d pages, %d checkboxes' % (out, n_pages, nbox))


if __name__ == '__main__':
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    with open(sys.argv[1], encoding='utf-8') as f:
        data = json.load(f)
    items = data['items'] if isinstance(data, dict) else data
    heading = data.get('heading', 'Next session') if isinstance(data, dict) else 'Next session'
    # Questions are asked in chat, never printed one per page (owner, 2026-09-05).
    questions = [it for it in items if it.get('tier') == 'Answer']
    checks = [it for it in items if it.get('tier') != 'Answer']
    if questions:
        print('QUESTIONS (ask these in the chat message, not in the PDF):')
        for q in questions:
            for ch in q['checks']:
                print('- [%s] %s' % (q['ref'], ch))
        print()
    build(checks, sys.argv[2], heading)
