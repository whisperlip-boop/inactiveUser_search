#!/usr/bin/env python3
"""src/main/resources/inactive-user-search.properties 생성기.

Jira(Java 8)는 플러그인 i18n .properties를 ISO-8859-1로 읽기 때문에 한글을 그대로 넣으면
관리 화면과 JQL 자동완성에서 글자가 깨진다. 한글은 반드시 \\uXXXX로 이스케이프해야 하는데,
손으로 관리하기엔 읽을 수가 없어서 이 스크립트가 원문을 들고 있고 결과물만 생성한다.

    python3 tools/make-i18n.py
"""

import os

MESSAGES = [
    ("inactiveuser.function.inactiveuser.name",
     "inactiveUser() - 퇴사(비활성) 계정 검색"),
    ("inactiveuser.function.inactiveuser.desc",
     '퇴사·비활성 처리된 계정을 사용자명, 표시이름, 이메일 일부로 찾는다. '
     '예: reporter in inactiveUser("hong.gildong")'),
    ("inactiveuser.function.anyuser.name",
     "anyUser() - 계정 상태 무관 사용자 검색"),
    ("inactiveuser.function.anyuser.desc",
     '활성·비활성·삭제 계정을 가리지 않고 사용자명, 표시이름, 이메일 일부로 찾는다. '
     '예: reporter in anyUser("gildong")'),
    ("inactiveuser.function.warn.nomatch",
     "{0}: [{1}] 와 일치하는 사용자가 없습니다. "
     "사용자명 대신 표시이름이나 이메일 일부로도 찾을 수 있습니다."),
    ("inactiveuser.function.warn.truncated",
     "{0}: 일치하는 사용자가 너무 많아 {1}명까지만 검색합니다. 검색어를 더 구체적으로 지정하세요."),
    # 자동완성 드롭다운에서 활성 사용자와 구분하는 꼬리표
    ("inactiveuser.suggestion.marker.inactive", "비활성"),
    ("inactiveuser.suggestion.marker.deleted", "삭제된 계정"),
]

HEADER = [
    "# inactive-user-search i18n",
    "# 한글 값은 native2ascii 규칙(\\uXXXX)으로 인코딩되어 있다. 수정 시 이 파일을 직접 고치지 말고",
    "# tools/make-i18n.py 를 고쳐서 재생성할 것.",
    "",
]


def escape(text):
    return "".join(c if ord(c) < 128 else "\\u%04x" % ord(c) for c in text)


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    target = os.path.join(root, "src", "main", "resources", "inactive-user-search.properties")

    lines = [escape(line) for line in HEADER]
    lines += ["%s=%s" % (key, escape(value)) for key, value in MESSAGES]

    with open(target, "w", encoding="ascii") as handle:
        handle.write("\n".join(lines) + "\n")
    print("wrote %s (%d messages)" % (target, len(MESSAGES)))


if __name__ == "__main__":
    main()
