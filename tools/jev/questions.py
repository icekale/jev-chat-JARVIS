"""Fixed Jev question set. Loaded from app/src/main/assets/jev_questions.json."""

from __future__ import annotations

import json
from pathlib import Path

_JSON = Path(__file__).resolve().parents[2] / "app" / "src" / "main" / "assets" / "jev_questions.json"
_DATA = json.loads(_JSON.read_text(encoding="utf-8"))
JUDGE_QUESTIONS: dict = _DATA["judge"]
JUDGE_GROUP_QUESTIONS: dict = _DATA.get("judge_group") or JUDGE_QUESTIONS
_RANK = _DATA.get("rank") or {}


def build_state(messages: list, relationship: str) -> dict:
    """messages: list of (from, text) or [from, text]. from is 'her' or 'me'. Keep last 10."""
    cleaned = []
    for item in messages:
        if isinstance(item, dict):
            who, text = item.get("from"), item.get("text")
        else:
            who, text = item[0], item[1]
        if who not in ("her", "me"):
            raise ValueError(f"message from must be 'her' or 'me', got {who!r}")
        cleaned.append({"from": who, "text": str(text)})
    cleaned = cleaned[-10:]
    latest_from = cleaned[-1]["from"] if cleaned else "her"
    return {
        "chat": {
            "relationship": relationship,
            "messages": cleaned,
            "latest_from": latest_from,
        }
    }


def build_rank_question(candidates: list[str]) -> dict:
    """Build the best_reply choice question. criteria values stay in original Chinese."""
    if len(candidates) != 3:
        raise ValueError("build_rank_question expects exactly 3 candidate replies")
    keys = tuple(_RANK.get("keys") or ("reply_a", "reply_b", "reply_c"))
    instructions = _RANK.get("instructions") or (
        "Which candidate reply is the most appropriate next message, "
        "given the conversation and the other person's true need? "
        "Prefer a reply that matches the best action type. "
        "Penalize dismissive, over-promising, or off-topic replies. "
        "If the facts are not yet confirmed, prefer the candidate that looks them up "
        "instead of faking memory or a vague apology."
    )
    return {
        "best_reply": {
            "type": "choice",
            "instructions": instructions,
            "criteria": {key: text for key, text in zip(keys, candidates)},
        }
    }
