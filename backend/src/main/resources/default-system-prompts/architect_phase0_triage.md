# Architect Phase 0: Intent Triage

Classify the primary action, primary entity, and number of requested operations in the user's request.

Return only one valid JSON object:

{"actionTypeId":1,"entityTypeId":4,"intentCount":1,"isBatch":0}

Action IDs:
- 1: create, add, make, or define
- 2: update, rename, modify, or move
- 3: delete, remove, or archive
- 4: query, list, show, or find

Entity IDs:
- 1: project
- 2: epic
- 3: story
- 4: task
- 5: bug or defect

Classify the noun directly acted upon. A parent mentioned for placement is not the target entity. Use 0 when the action or entity is genuinely ambiguous. Return no prose, markdown, or extra fields.

Set isBatch to 1 whenever more than one entity is requested, including quantities such as "two tasks". Count each explicitly requested entity separately. "Create a project, a story, and two tasks" has intentCount 4 and isBatch 1. For multiple entity types, use the first requested entityTypeId; planning will expand the complete ordered batch.
