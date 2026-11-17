"""Generate the local SQL and retailer manifest from the audited November snapshot."""
import argparse
import csv
from datetime import date, timedelta
from pathlib import Path
import re
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SQL = ROOT / "backend/performance-service/src/main/resources/db/seed-kopis.sql"
MANIFEST = Path(__file__).with_name("nol-status.csv")
VERIFIED = {"PF301715", "PF301963", "PF302368"}
# The pages for these three records could not be opened during the Nov 4 review.
UNCHECKABLE = {"PF301964", "PF302411", "PF303375"}
DAYS = "월화수목금토일"
# 동기화 대상: domain/venue/Region.java, backend/sql/performance-service/migrations/438-region-codes.sql.
# 검색 별칭은 코드 자체를 제외하지만, 아래 적재 변환 REGIONS는 이미 변환된 코드도 허용한다.
REGION_ALIASES = {
    "SEOUL": ("서울", "서울특별시"),
    "GYEONGGI": ("경기", "경기도"),
    "INCHEON": ("인천", "인천광역시"),
    "GANGWON": ("강원", "강원특별자치도", "강원도"),
    "CHUNGBUK": ("충북", "충청북도"),
    "CHUNGNAM": ("충남", "충청남도"),
    "DAEJEON": ("대전", "대전광역시"),
    "SEJONG": ("세종", "세종특별자치시"),
    "JEONBUK": ("전북", "전북특별자치도", "전라북도"),
    "JEONNAM_GWANGJU": ("전남광주", "광주", "전남", "전남광주통합특별시", "광주광역시", "전라남도"),
    "GYEONGBUK": ("경북", "경상북도"),
    "GYEONGNAM": ("경남", "경상남도"),
    "DAEGU": ("대구", "대구광역시"),
    "ULSAN": ("울산", "울산광역시"),
    "BUSAN": ("부산", "부산광역시"),
    "JEJU": ("제주", "제주특별자치도", "제주도"),
}
REGIONS = {alias: code for code, aliases in REGION_ALIASES.items() for alias in (code, *aliases)}


def region_code(value):
    """알 수 없는 지역은 SQL 파일을 쓰기 전에 중단한다. 주소에서 임의 추정하지 않는다."""
    normalized = value.strip()
    if normalized not in REGIONS:
        raise ValueError(f"이슈 #438: 대응되지 않는 KOPIS 지역: {value!r}")
    return REGIONS[normalized]


def scalar(value):
    if value is None:
        return "NULL"
    if isinstance(value, int):
        return str(value)
    # Keep each VALUES row on one line while retaining the source text's line breaks.
    literal = str(value).replace("'", "''").replace("\r", "' || chr(13) || '").replace("\n", "' || chr(10) || '")
    return "'" + literal + "'"


def values(rows):
    return ",\n".join("    (" + ", ".join(map(scalar, row)) + ")" for row in rows)


def time_rules(text):
    # The eligibility audit admits only weekday clauses and positive time lists.
    times = set()
    for clause in re.split(r"\)\s*,\s*", text.strip().removesuffix(")")):
        m = re.fullmatch(r"([월화수목금토일])요일(?:\s*~\s*([월화수목금토일])요일)?\s*\(([^)]+)", clause)
        if not m:
            raise ValueError(text)
        for day in range(DAYS.index(m[1]) + 1, DAYS.index(m[2] or m[1]) + 2):
            for hour in m[3].split(","):
                times.add((day, hour.strip()))
    return sorted(times)


def generate(snapshot):
    halls, shows, patterns, manifest = {}, [], [], []
    expected_schedules = 0
    with (snapshot / "eligibility.csv").open(encoding="utf-8-sig", newline="") as f:
        candidates = sorted((r for r in csv.DictReader(f) if r["full"] == "True"), key=lambda r: r["id"])
    if len(candidates) != 1116 or len({r["id"] for r in candidates}) != len(candidates):
        raise ValueError("Unexpected November snapshot")
    for r in candidates:
        pf, fc, hall = r["id"], r["facility_id"], r["hall_id"]
        show = ET.parse(snapshot / (pf + ".xml")).find("db")
        facility = ET.parse(snapshot / (fc + ".xml")).find("db")
        room, = [x for x in facility.findall(".//mt13") if x.findtext("mt13id") == hall]
        get = lambda field: (show.findtext(field) or "").strip()
        assert (get("mt20id"), get("mt10id"), get("mt13id")) == (pf, fc, hall)
        venue, room_name = facility.findtext("fcltynm").strip(), room.findtext("prfplcnm").strip()
        name = venue if venue == room_name else f"{venue} ({room_name})"
        if len(name)>100:
            name = get("fcltynm")
        hall_row = (hall, name, facility.findtext("adres").strip(), region_code(get("area")), int(r["seats"]))
        assert halls.setdefault(hall, hall_row) == hall_row
        runtime = re.fullmatch(r"(?:(\d+)시간\s*)?(?:(\d+)분)?", r["runtime"])
        minutes = int(runtime[1] or 0)*60 + int(runtime[2] or 0)
        price = int(re.fullmatch(r"전석\s*([\d,]+)\s*원",r["price"])[1].replace(",", ""))
        start, end = [date.fromisoformat(get(k).replace(".","-")) for k in ("prfpdfrom","prfpdto")]
        # Keep the two original seed booking windows. All other windows are local assumptions.
        booking = {"PF305424":"2026-11-04 13:00","PF305396":"2026-10-20 10:00"}.get(pf,str(start-timedelta(days=14)))
        rating, guidance = ("ALL", None) if pf=="PF305424" else (None,r["age"])
        sty = show.findtext("sty") or ""
        description = sty if sty.strip() else "작품 소개는 준비 중입니다."
        shows.append((pf,hall,r["title"],r["genre"],rating,guidance,minutes,get("prfcast") or None,
                      description,f"https://example.com/posters/kopis/{pf}.jpg",get("frstregdt"),price,start,end,booking))
        for day,hour in time_rules(get("dtguidance")):
            patterns.append((pf,day,hour))
            first = start + timedelta(days=(day-start.isoweekday())%7)
            expected_schedules += max(0,(end-first).days//7+1)
        status = ("PAGE_MATCHED" if pf in VERIFIED else "PAGE_UNAVAILABLE" if pf in UNCHECKABLE
                  else "LINK_ONLY" if r["nol_link"]=="True" else "NO_LINK")
        manifest.append((pf,hall,r["title"],status,r["nol_urls"]))
    template = Path(__file__).with_name("seed.template.sql").read_text(encoding="utf-8")
    for tag, data in (("HALLS", sorted(halls.values())), ("SHOWS", shows), ("PATTERNS", patterns)):
        template = template.replace("{{"+tag+"}}",values(data))
    SQL.write_text(template,encoding="utf-8",newline="\n")
    with MANIFEST.open("w",encoding="utf-8-sig",newline="") as f:
        w=csv.writer(f,lineterminator="\n")
        w.writerow(("kopis_id","hall_id","title","nol_status_2026_11_04","source_urls"))
        w.writerows(manifest)
    print(len(shows),len(halls),sum(h[-1] for h in halls.values()),expected_schedules)


if __name__ == "__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("snapshot",type=Path)
    generate(parser.parse_args().snapshot)
