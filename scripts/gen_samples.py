#!/usr/bin/env python3
"""Generates the fictional demo HR and Finance files (samples/ and backend demo resources).

All names are invented. Email addresses use the reserved .example domain.
Run: python scripts/gen_samples.py
"""
import csv, io, os
from decimal import Decimal

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIRS = [os.path.join(ROOT, 'samples'), os.path.join(ROOT, 'backend', 'src', 'main', 'resources', 'demo')]

UNITS = [  # code, en, sw, type, parent
    ('DG', 'Office of the Director General', 'Ofisi ya Mkurugenzi Mkuu', 'OFFICE', ''),
    ('DRD', 'Directorate of Research and Development', 'Kurugenzi ya Utafiti na Maendeleo', 'DIRECTORATE', 'DG'),
    ('LAB-CHEM', 'Chemical and Environmental Laboratory', 'Maabara ya Kemikali na Mazingira', 'LABORATORY', 'DRD'),
    ('LAB-MECH', 'Mechanical and Materials Laboratory', 'Maabara ya Mitambo na Malighafi', 'LABORATORY', 'DRD'),
    ('DIS', 'Directorate of Industrial Services', 'Kurugenzi ya Huduma za Viwanda', 'DIRECTORATE', 'DG'),
    ('SEC-TT', 'Technology Transfer Section', 'Sehemu ya Uhawilishaji wa Teknolojia', 'SECTION', 'DIS'),
    ('DCS', 'Directorate of Corporate Services', 'Kurugenzi ya Huduma za Shirika', 'DIRECTORATE', 'DG'),
    ('ICTT', 'ICTT Department', 'Idara ya TEHAMA', 'DEPARTMENT', 'DCS'),
    ('FIN', 'Finance and Accounts Unit', 'Kitengo cha Fedha na Uhasibu', 'UNIT', 'DCS'),
    ('HRA', 'Human Resources Unit', 'Kitengo cha Rasilimali Watu', 'UNIT', 'DCS'),
    ('CPR', 'Communication and Public Relations Unit', 'Kitengo cha Mawasiliano na Uhusiano', 'UNIT', 'DCS'),
]
HEAD_TITLES = {
    'DG': ('Director General', 'Mkurugenzi Mkuu'),
    'DRD': ('Director of Research and Development', 'Mkurugenzi wa Utafiti na Maendeleo'),
    'LAB-CHEM': ('Head, Chemical Laboratory', 'Mkuu wa Maabara ya Kemikali'),
    'LAB-MECH': ('Head, Mechanical Laboratory', 'Mkuu wa Maabara ya Mitambo'),
    'DIS': ('Director of Industrial Services', 'Mkurugenzi wa Huduma za Viwanda'),
    'SEC-TT': ('Head, Technology Transfer', 'Mkuu wa Uhawilishaji wa Teknolojia'),
    'DCS': ('Director of Corporate Services', 'Mkurugenzi wa Huduma za Shirika'),
    'ICTT': ('Head, ICTT Department', 'Mkuu wa Idara ya TEHAMA'),
    'FIN': ('Chief Accountant', 'Mhasibu Mkuu'),
    'HRA': ('Head, Human Resources', 'Mkuu wa Rasilimali Watu'),
    'CPR': ('Head, Communication', 'Mkuu wa Mawasiliano'),
}
OFFICER_TITLES = {
    'DG': ('Personal Assistant', 'Msaidizi Binafsi'),
    'LAB-CHEM': ('Research Scientist', 'Mwanasayansi Mtafiti'),
    'LAB-MECH': ('Research Engineer', 'Mhandisi Mtafiti'),
    'SEC-TT': ('Industrial Liaison Officer', 'Afisa Uhusiano wa Viwanda'),
    'ICTT': ('ICT Officer', 'Afisa TEHAMA'),
    'FIN': ('Accountant', 'Mhasibu'),
    'HRA': ('Human Resources Officer', 'Afisa Rasilimali Watu'),
    'CPR': ('Communication Officer', 'Afisa Mawasiliano'),
}

# employee_no, title, first, middle, last, unit, head?, category, station, mobile
STAFF = [
    ('TR1001', 'Dr.', 'Amani', 'J.', 'Kilonzo', 'DG', True, 'Administrative', 'Dar es Salaam HQ', '+255700100101'),
    ('TR1002', '', 'Rehema', '', 'Mwakasege', 'DG', False, 'Administrative', 'Dar es Salaam HQ', '+255700100102'),
    ('TR1010', 'Prof.', 'Baraka', 'S.', 'Nyirenda', 'DRD', True, 'Research', 'Dar es Salaam HQ', '+255700100110'),
    ('TR1011', 'Dr.', 'Zawadi', '', 'Mrema', 'LAB-CHEM', True, 'Research', 'Dar es Salaam HQ', '+255700100111'),
    ('TR1007', '', 'Neema', 'A.', 'Mushi', 'LAB-CHEM', False, 'Research', 'Dar es Salaam HQ', '+255700100107'),
    ('TR1012', '', 'Juma', '', 'Lyimo', 'LAB-CHEM', False, 'Technical', 'Dar es Salaam HQ', '+255700100112'),
    ('TR1013', 'Eng.', 'Faraji', 'M.', 'Komba', 'LAB-MECH', True, 'Research', 'Dar es Salaam HQ', '+255700100113'),
    ('TR1014', 'Eng.', 'Halima', '', 'Ngowi', 'LAB-MECH', False, 'Research', 'Dar es Salaam HQ', '+255700100114'),
    ('TR1015', '', 'Daudi', '', 'Massawe', 'LAB-MECH', False, 'Technical', 'Arusha Field Office', '+255700100115'),
    ('TR1020', 'Dr.', 'Upendo', 'K.', 'Shayo', 'DIS', True, 'Research', 'Dar es Salaam HQ', '+255700100120'),
    ('TR1021', '', 'Imani', '', 'Temba', 'SEC-TT', True, 'Technical', 'Dar es Salaam HQ', '+255700100121'),
    ('TR1022', '', 'Saidi', '', 'Mbwambo', 'SEC-TT', False, 'Technical', 'Mwanza Field Office', '+255700100122'),
    ('TR1030', '', 'Grace', 'L.', 'Massawe', 'DCS', True, 'Administrative', 'Dar es Salaam HQ', '+255700100130'),
    ('TR1031', 'Eng.', 'Elia', '', 'Mollel', 'ICTT', True, 'Technical', 'Dar es Salaam HQ', '+255700100131'),
    ('TR1032', '', 'Tumaini', '', 'Kweka', 'ICTT', False, 'Technical', 'Dar es Salaam HQ', '+255700100132'),
    ('TR1040', 'CPA', 'Mwanaisha', '', 'Said', 'FIN', True, 'Administrative', 'Dar es Salaam HQ', '+255700100140'),
    ('TR1041', '', 'Petro', '', 'Urio', 'FIN', False, 'Administrative', 'Dar es Salaam HQ', '+255700100141'),
    ('TR1042', '', 'Lucy', '', 'Minja', 'FIN', False, 'Administrative', 'Dar es Salaam HQ', '+255700100142'),
    ('TR1050', '', 'Esther', '', 'Kimaro', 'HRA', True, 'Administrative', 'Dar es Salaam HQ', '+255700100150'),
    ('TR1051', '', 'Hassani', '', 'Mfinanga', 'HRA', False, 'Administrative', 'Dar es Salaam HQ', '+255700100151'),
    ('TR1060', '', 'Agnes', '', 'Swai', 'CPR', True, 'Administrative', 'Dar es Salaam HQ', '+255700100160'),
    ('TR1061', '', 'Yusufu', '', 'Chande', 'CPR', False, 'Administrative', 'Dar es Salaam HQ', '+255700100161'),
]
PARENT = {u[0]: u[4] for u in UNITS}


def manager_of(s):
    unit = s[5]
    head = lambda u: next((x[0] for x in STAFF if x[5] == u and x[6]), None)
    if not s[6]:
        return head(unit) or ''
    p = PARENT[unit]
    while p:
        h = head(p)
        if h:
            return h
        p = PARENT[p]
    return ''


def write(name, rows):
    buf = io.StringIO()
    w = csv.writer(buf, lineterminator='\n')
    w.writerows(rows)
    data = buf.getvalue().encode('utf-8')
    for d in OUT_DIRS:
        sub = os.path.join(d, os.path.dirname(name))
        os.makedirs(sub, exist_ok=True)
        with open(os.path.join(d, name), 'wb') as f:
            f.write(data)
    print('wrote', name, len(rows), 'rows')


def hr():
    rows = [['U', c, en, sw, t, p, '2020-07-01', ''] for c, en, sw, t, p in UNITS]
    pos = []
    for c, *_ in UNITS:
        en, sw = HEAD_TITLES[c]
        pos.append(['O', f'P-{c}-HEAD', en, sw, c, 'true'])
        if c in OFFICER_TITLES:
            en, sw = OFFICER_TITLES[c]
            pos.append(['O', f'P-{c}-OFF', en, sw, c, 'false'])
    rows += pos
    for s in STAFF:
        emp, title, first, middle, last, unit, head, cat, station, mobile = s
        position = f'P-{unit}-HEAD' if head else f'P-{unit}-OFF'
        email = f'{first.lower()}.{last.lower()}@tirdo.example'
        rows.append(['S', emp, first, middle, last, title, position, unit, manager_of(s), 'ACTIVE', cat, station, email,
                     '+255 22 266 ' + emp[-4:], mobile])
    rows.append(['T', len(UNITS), len(pos), len(STAFF)])
    write('hr/hr-demo-2026-10-01.csv', rows)


def finance():
    D = Decimal
    # Neema Mushi (TR1007) mirrors the base blueprint: TZS 3.5M approved, 2.0M paid, 1.5M outstanding.
    claims = [
        ('CLM-2026-0412', 'TR1007', 'TRAVEL', 'Travel allowance - Mwanza field sampling', '1200000.00', '2026-07-14', 'PV-26-1180'),
        ('CLM-2026-0457', 'TR1007', 'PER_DIEM', 'Per diem - Tanga cement plant audit (8 days)', '800000.00', '2026-08-05', 'PV-26-1304'),
        ('CLM-2026-0533', 'TR1007', 'CONSULTANCY', 'Consultancy allowance - water quality study for Morogoro industries', '1500000.00', '2026-09-02', 'PV-26-1421'),
        ('CLM-2026-0418', 'TR1014', 'TRAVEL', 'Travel allowance - Arusha equipment calibration', '650000.00', '2026-07-21', 'PV-26-1190'),
        ('CLM-2026-0502', 'TR1014', 'EXTRA_DUTY', 'Extra duty - weekend furnace commissioning', '240000.00', '2026-08-26', 'PV-26-1377'),
        ('CLM-2026-0471', 'TR1022', 'PER_DIEM', 'Per diem - Mwanza SME technology clinic (5 days)', '500000.00', '2026-08-12', 'PV-26-1322'),
        ('CLM-2026-0489', 'TR1012', 'TRAVEL', 'Travel allowance - Dodoma sample delivery', '320000.00', '2026-08-19', 'PV-26-1350'),
        ('CLM-2026-0544', 'TR1032', 'EXTRA_DUTY', 'Extra duty - data centre power maintenance', '180000.00', '2026-09-09', 'PV-26-1440'),
        ('CLM-2026-0561', 'TR1061', 'OTHER', 'Refund - exhibition materials for Sabasaba stand', '275000.00', '2026-09-16', 'PV-26-1466'),
    ]
    payments = [
        ('PAY-2026-0901', 'TR1007', '1200000.00', '2026-07-28', 'NMB-77120931'),
        ('PAY-2026-0955', 'TR1007', '500000.00', '2026-08-29', 'NMB-77198410'),
        ('PAY-2026-1012', 'TR1007', '300000.00', '2026-09-30', 'NMB-77260177'),
        ('PAY-2026-0911', 'TR1014', '650000.00', '2026-08-04', 'CRDB-5510273'),
        ('PAY-2026-0968', 'TR1022', '250000.00', '2026-09-05', 'NMB-77210882'),
        ('PAY-2026-0974', 'TR1012', '320000.00', '2026-09-08', 'NMB-77221005'),
    ]
    allocations = [
        ('ALC-2026-1501', 'PAY-2026-0901', 'CLM-2026-0412', '1200000.00', ''),
        ('ALC-2026-1544', 'PAY-2026-0955', 'CLM-2026-0457', '500000.00', ''),
        ('ALC-2026-1602', 'PAY-2026-1012', 'CLM-2026-0533', '300000.00', ''),
        ('ALC-2026-1510', 'PAY-2026-0911', 'CLM-2026-0418', '650000.00', ''),
        ('ALC-2026-1551', 'PAY-2026-0968', 'CLM-2026-0471', '250000.00', ''),
        ('ALC-2026-1558', 'PAY-2026-0974', 'CLM-2026-0489', '320000.00', ''),
    ]
    rows = [['H', 'FIN-EXPORT-2026-10-01', '2026-10-01T06:00:00+03:00', 'FINANCE', '2026-Q1']]
    rows += [['C', r, e, t, d, a, 'TZS', dt, v, 'APPROVED'] for r, e, t, d, a, dt, v in claims]
    rows += [['P', r, e, a, 'TZS', dt, ref, 'CONFIRMED'] for r, e, a, dt, ref in payments]
    rows += [['A', r, p, c, a, rev] for r, p, c, a, rev in allocations]
    total = lambda xs, i: sum(D(x[i]) for x in xs)
    rows.append(['T', len(claims) + len(payments) + len(allocations), f'{total(claims, 4):.2f}', f'{total(payments, 2):.2f}',
                 f'{total(allocations, 3):.2f}'])
    write('finance/finance-demo-2026-10-01.csv', rows)

    # A second export with problems Finance must resolve: unknown employee, over-allocation, currency mismatch.
    claims2 = [
        ('CLM-2026-0590', 'TR1022', 'TRAVEL', 'Travel allowance - Kigoma palm oil cluster visit', '410000.00', 'TZS', '2026-10-02', 'PV-26-1502'),
        ('CLM-2026-0591', 'TR9999', 'TRAVEL', 'Travel allowance - name not in HR master', '150000.00', 'TZS', '2026-10-02', 'PV-26-1503'),
        ('CLM-2026-0592', 'TR1015', 'PER_DIEM', 'Per diem - Nairobi standards workshop', '600.00', 'USD', '2026-10-03', 'PV-26-1504'),
    ]
    payments2 = [
        ('PAY-2026-1040', 'TR1022', '410000.00', 'TZS', '2026-10-08', 'NMB-77290041'),
        ('PAY-2026-1041', 'TR1015', '600000.00', 'TZS', '2026-10-08', 'NMB-77290042'),
    ]
    allocations2 = [
        ('ALC-2026-1640', 'PAY-2026-1040', 'CLM-2026-0590', '410000.00', ''),
        ('ALC-2026-1641', 'PAY-2026-0968', 'CLM-2026-0471', '300000.00', ''),   # exceeds what the payment covers
        ('ALC-2026-1642', 'PAY-2026-1041', 'CLM-2026-0592', '600.00', ''),      # TZS payment against a USD claim
    ]
    rows = [['H', 'FIN-EXPORT-2026-10-09', '2026-10-09T06:00:00+03:00', 'FINANCE', '2026-Q2']]
    rows += [['C', r, e, t, d, a, cur, dt, v, 'APPROVED'] for r, e, t, d, a, cur, dt, v in claims2]
    rows += [['P', r, e, a, cur, dt, ref, 'CONFIRMED'] for r, e, a, cur, dt, ref in payments2]
    rows += [['A', r, p, c, a, rev] for r, p, c, a, rev in allocations2]
    rows.append(['T', len(claims2) + len(payments2) + len(allocations2), f'{total(claims2, 4):.2f}', f'{total(payments2, 2):.2f}',
                 f'{total(allocations2, 3):.2f}'])
    write('finance/finance-demo-2026-10-09-with-exceptions.csv', rows)


if __name__ == '__main__':
    hr()
    finance()
