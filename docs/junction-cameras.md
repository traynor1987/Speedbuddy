# Manual junction camera groups

Create a four-way or five-way junction on Map, name it (for example Fiveways),
then place each individual speed, red-light or combined camera. Four/five refers
to connecting roads, not a required camera count. Each camera keeps its actual
location, enforced travel direction and optional speed limit. No road geometry
or camera coverage is inferred from the number of roads.

Grouped cameras use the existing camera repository and alert detector. Only a
relevant approaching camera supplies the live type, distance and limit. Approach,
100-yard and speeding cues share one junction encounter, preventing multiple
members from competing. Passing remains per camera so a further relevant member
can retain the card. Leaving the existing 850m encounter area rearms the group.

Map provides a distinct four/five-road junction marker and a management sheet:
rename/change road count; add cameras by dropping real pins; edit or remove a
member; ungroup the junction while keeping its cameras. A grouped camera must
have a known direction, a supported fixed-camera type and lie within 300m of the
centre. Editing is available while parked. Drafts survive rotation; back/cancel
does not save unfinished changes.

SQLite schema 10 adds junctions and membership tables without rewriting existing
camera/import/correction/mobile data. Owner backup schema 8 preserves groups and
memberships, including empty groups; versions 1–7 remain readable. Group edits,
membership changes and restoration are transactional. Invalid/dangling/duplicate
backup groups and unsupported members are rejected before restoration.

## Acceptance

1. Park, open Map → Add → 4/5-way junction. Choose the centre, name and road count.
2. Open its marker → Add camera, place a real camera on its approach, choose speed,
   red light or both, set enforced travel direction and optional limit. Repeat
   for the other cameras; do not invent cameras for roads without enforcement.
3. Edit one member, restart/rotate, export and restore into an isolated install.
   Names, road count, individual camera positions and memberships must survive.
4. Approach in one recorded direction: only relevant cameras warn, sharing one
   usual announcement, one 100-yard reminder and one speeding cue per encounter.
   Use simulated GPS for speeding checks. Stop and resume; do not repeat cues.
5. Pass the junction, leave the encounter area and return on another approach.
   Check correct camera/direction/limit and fresh warnings. Test unrelated nearby
   cameras, opposite/parallel roads, fixed switch, offline operation and voice off.
6. Remove one camera without removing the group. Ungroup a junction and verify
   all remaining cameras stay as ordinary owner cameras. Old backup imports and
   existing imported/mobile/road corrections must still work.

Names and camera placement are owner observations, not verified external data.
Direction and road geometry remain necessary for relevant warnings; unknown or
ambiguous road coverage cannot guarantee every carriageway rejection.
