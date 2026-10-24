"""Makes the word list the helper uses to fix misread words.

Common English words, most common first, plus game words.
Runs during the GitHub build. Usage: python3 make_words.py OUTPUT_FILE
"""
import re
import sys

from wordfreq import top_n_list

EXTRA = """
spin spins jackpot jackpots bonus bonuses coins coin slots slot reels reel collect claim
lobby vip mega grand mini minor major wild wilds scatter payout payline paylines bet
autoplay freespins multiplier leaderboard tournament quest quests daily rewards reward
unlock unlocked locked shop store gems gem chest chests booster boosters level levels
settings options inbox mailbox xp hp pvp pve ok gg lvl
""".split()

out, seen = [], set()
for w in top_n_list("en", 60000) + EXTRA:
    w = w.lower()
    if re.fullmatch(r"[a-z]+", w) and w not in seen and (len(w) > 1 or w in ("a", "i")):
        seen.add(w)
        out.append(w)

if len(out) < 20000:
    sys.exit(f"Word list too small: {len(out)}")
with open(sys.argv[1], "w") as f:
    f.write("\n".join(out))
print(f"Wrote {len(out)} words")
