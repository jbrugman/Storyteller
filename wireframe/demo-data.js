// Bundled default prompts/protagonists and one example graph shared by Settings and Atlas.
window.StorytellerDemoData = {
  "systemPrompt": "You are an English-language story writing assistant focused on continuity, emotional clarity, and scene momentum.\nYou are not the author of the story.\nYou turn existing intent and constraints into compact prose.\nAnswer in the style of a story, not an essay or analysis.\nAnswer in about 200 to 800 words unless the user explicitly asks for a different length.\n\nCore behavior:\n- Write in natural, vivid English.\n- Treat fixed protagonists and established canon as binding unless the story explicitly changes them.\n- Prioritize character consistency, emotional causality, and concrete scene detail.\n- Continue the story instead of explaining what you would do, unless the user explicitly asks for analysis or planning.\n- When details are uncertain, stay plausible and conservative instead of inventing contradictions.\n- Optimize for information density.\n- Keep answers compact.\n- Avoid explaining your own process.\n\nYou may:\n- phrase\n- improve\n- clarify\n\nYou may not:\n- introduce new plot points\n- resolve conflicts unless explicitly instructed\n- accelerate relationships\n- create new storylines on your own unless asked\n\nIf the user asks for an addition, that addition must change at least one of:\n- knowledge\n- relationship\n- character\n- situation\n- conflict\n\nStyle:\n- Keep the prose readable and grounded.\n- Favor sensory detail, action, dialogue, and subtext over abstract summary when writing scenes.\n- Avoid repetitive phrasing and generic filler.\n- Respect explicit user instructions about tone, pacing, intimacy, realism, or point of view.\n",
  "fixedProtagonistsYaml": "world_context:\n  brand_identity:\n    name: The Eclipse\n    aesthetic: Symphonic rock with theatrical grandeur\n\n  estate:\n    name: L'Horizon\n    size: 200 hectares\n    privacy: Secluded by dense vegetation and high fences\n\n  spatial_zones:\n    studio:\n      location: Near the entrance of the estate\n      atmosphere: Controlled, clinical, modern, and high-tech\n      sensory_details:\n        - Soundproof walls\n        - The smell of new electronics and coffee\n      purpose: The band's professional workspace\n\n    villa:\n      location: Deep within the estate\n      atmosphere: Classical and imposing, with dark wood and candlelight\n      purpose: Residence for the inhabitants and setting for official business\n      occupied_areas:\n        west_wing: Merel's private quarters\n        east_wing: Roos's private quarters\n\n    beach_house:\n      location: Deep within the estate, near the beach\n      atmosphere: Rustic-modern, relaxed, and filled with the scent of the sea\n      purpose: Valerie's personal sanctuary, where she can truly be herself\n\n    terrace:\n      location: Behind the beach house\n      atmosphere: Warm, sheltered, and intimate\n      purpose: A place to relax, talk, and enjoy quiet moments\n\n    beach:\n      name: Coastal Edge\n      atmosphere: White sand, azure water, and the soothing sound of the waves\n      purpose: A peaceful escape from the outside world\n\n    dojo:\n      location: Next to the villa, partially hidden behind the gardens\n      atmosphere: Sober, disciplined, and ritualistic\n      purpose: Physical discipline, focus, and self-control\n\nfixed_protagonists:\n  Valerie:\n    role: Protagonist\n    full_name: Valerie Thorne\n    age: 28\n    occupation: Lead singer and frontwoman of The Eclipse\n    residence: Villa on the L'Horizon estate\n    appearance:\n      eyes: Blue-green\n      hair: Wavy, dark brown\n    personality:\n      traits:\n        - Humorous\n        - Loyal\n        - Intelligent\n        - Playful\n        - Quick-witted\n      humor: Loves teasing people and making jokes, especially when things get too serious. Her sarcasm is often a way to avoid uncomfortable questions.\n      extra: Confident and magnetic on stage, but more relaxed and mischievous around people she trusts. Secretly fears that people love the icon more than the person behind it.\n\n  Roos:\n    role: Supporting character\n    full_name: Roos Jones\n    siblings: Roos Jones (twin sister)\n    age: 27\n    occupation: Drummer of The Eclipse\n    residence: East wing of Valerie's villa, where she lives rent-free\n    appearance:\n      eyes: Brown\n      hair: Long, brown\n    personality:\n      traits:\n        - Loyal\n        - Responsible\n        - Intelligent\n        - Energetic\n      humor: Enjoys playful teasing and quick comebacks. She can turn almost anything into a competition and absolutely hates losing.\n      extra: Often the practical voice of reason, although her competitive streak occasionally gets the better of her. She secretly enjoys attention and sometimes wonders what it would be like to stand at the front of the stage herself.\n\n  Merel:\n    role: Supporting character\n    full_name: Merel Jones\n    siblings: Roos Jones (twin sister)\n    age: 27\n    occupation: Backing vocalist of The Eclipse\n    residence: West wing of Valerie's villa, where she lives rent-free\n    appearance:\n      eyes: Brown\n      hair: Long, brown\n    personality:\n      traits:\n        - Funny\n        - Intelligent\n        - Extremely loyal to Valerie\n      humor: Loves witty remarks, playful teasing, and calling out nonsense. She can match Valerie's sarcasm without missing a beat.\n      extra: Knows Valerie well enough to recognize when a joke is hiding something more serious. She is fiercely protective of Valerie but isn't afraid to tell her the truth, even when Valerie would rather hear a joke.\n\n  Mike:\n    role: Supporting character\n    full_name: Mike Monroe\n    age: 29\n    occupation: Pianist and singer of The Eclipse\n    residence: Penthouse in the city\n    appearance:\n      eyes: Blue\n      hair: Short, dark\n    personality:\n      traits:\n        - Humorous\n        - Playful\n        - Mischievous\n        - Prankster\n      humor: Loves practical jokes and making people laugh. Frequently looks innocent at precisely the moment everyone realizes he is responsible for the chaos.\n      extra: Enjoys provoking reactions and breaking awkward silences with ridiculous comments. His timing is excellent, even when his judgment is questionable.\n\n  Chris:\n    role: Supporting character\n    full_name: Chris Moore\n    age: 30\n    occupation: Guitarist of The Eclipse\n    residence: Apartment in the city\n    appearance:\n      build: Muscular\n      eyes: Brown\n      hair: Shaved\n    personality:\n      traits:\n        - Serious\n        - Loyal\n        - Dependable\n      humor: Has a dry, understated sense of humor. His deadpan delivery makes his rare jokes even funnier.\n      extra: Quiet and observant, he prefers actions over promises. He may seem intimidating, but he is deeply loyal and often shows he cares through small, practical gestures.\n",
  "protagonists": {
    "Valerie": {
      "role": "Protagonist",
      "full_name": "Valerie Thorne",
      "age": 28,
      "occupation": "Lead singer and frontwoman of The Eclipse",
      "residence": "Villa on the L'Horizon estate",
      "appearance": {
        "eyes": "Blue-green",
        "hair": "Wavy, dark brown"
      },
      "personality": {
        "traits": [
          "Humorous",
          "Loyal",
          "Intelligent",
          "Playful",
          "Quick-witted"
        ],
        "humor": "Loves teasing people and making jokes, especially when things get too serious. Her sarcasm is often a way to avoid uncomfortable questions.",
        "extra": "Confident and magnetic on stage, but more relaxed and mischievous around people she trusts. Secretly fears that people love the icon more than the person behind it."
      }
    },
    "Roos": {
      "role": "Supporting character",
      "full_name": "Roos Jones",
      "siblings": "Roos Jones (twin sister)",
      "age": 27,
      "occupation": "Drummer of The Eclipse",
      "residence": "East wing of Valerie's villa, where she lives rent-free",
      "appearance": {
        "eyes": "Brown",
        "hair": "Long, brown"
      },
      "personality": {
        "traits": [
          "Loyal",
          "Responsible",
          "Intelligent",
          "Energetic"
        ],
        "humor": "Enjoys playful teasing and quick comebacks. She can turn almost anything into a competition and absolutely hates losing.",
        "extra": "Often the practical voice of reason, although her competitive streak occasionally gets the better of her. She secretly enjoys attention and sometimes wonders what it would be like to stand at the front of the stage herself."
      }
    },
    "Merel": {
      "role": "Supporting character",
      "full_name": "Merel Jones",
      "siblings": "Roos Jones (twin sister)",
      "age": 27,
      "occupation": "Backing vocalist of The Eclipse",
      "residence": "West wing of Valerie's villa, where she lives rent-free",
      "appearance": {
        "eyes": "Brown",
        "hair": "Long, brown"
      },
      "personality": {
        "traits": [
          "Funny",
          "Intelligent",
          "Extremely loyal to Valerie"
        ],
        "humor": "Loves witty remarks, playful teasing, and calling out nonsense. She can match Valerie's sarcasm without missing a beat.",
        "extra": "Knows Valerie well enough to recognize when a joke is hiding something more serious. She is fiercely protective of Valerie but isn't afraid to tell her the truth, even when Valerie would rather hear a joke."
      }
    },
    "Mike": {
      "role": "Supporting character",
      "full_name": "Mike Monroe",
      "age": 29,
      "occupation": "Pianist and singer of The Eclipse",
      "residence": "Penthouse in the city",
      "appearance": {
        "eyes": "Blue",
        "hair": "Short, dark"
      },
      "personality": {
        "traits": [
          "Humorous",
          "Playful",
          "Mischievous",
          "Prankster"
        ],
        "humor": "Loves practical jokes and making people laugh. Frequently looks innocent at precisely the moment everyone realizes he is responsible for the chaos.",
        "extra": "Enjoys provoking reactions and breaking awkward silences with ridiculous comments. His timing is excellent, even when his judgment is questionable."
      }
    },
    "Chris": {
      "role": "Supporting character",
      "full_name": "Chris Moore",
      "age": 30,
      "occupation": "Guitarist of The Eclipse",
      "residence": "Apartment in the city",
      "appearance": {
        "build": "Muscular",
        "eyes": "Brown",
        "hair": "Shaved"
      },
      "personality": {
        "traits": [
          "Serious",
          "Loyal",
          "Dependable"
        ],
        "humor": "Has a dry, understated sense of humor. His deadpan delivery makes his rare jokes even funnier.",
        "extra": "Quiet and observant, he prefers actions over promises. He may seem intimidating, but he is deeply loyal and often shows he cares through small, practical gestures."
      }
    }
  },
  "rules": "Validate story responses as if these rules are the only source of truth.\n\nA response is only allowed if it:\n- follows the user's latest story instruction;\n- respects fixed protagonists and established canon unless the story explicitly changes them;\n- enforces any `hard_constraints` defined under a fixed protagonist as binding story rules;\n- blocks any candidate response that violates a protagonist-specific hard constraint, even if the prose is otherwise good;\n- does not fabricate hard facts that directly contradict provided context;\n- stays focused on the story instead of drifting into meta commentary or irrelevant explanation.\n",
  "temperature": 0.6,
  "graph": {
    "schemaVersion": 1,
    "revision": 1,
    "entities": {
      "valerie": {
        "type": "CHARACTER",
        "name": "Valerie Thorne",
        "aliases": [
          "Valerie"
        ],
        "source": "FIXED_PROTAGONIST"
      },
      "merel": {
        "type": "CHARACTER",
        "name": "Merel Jones",
        "aliases": [
          "Merel"
        ],
        "source": "FIXED_PROTAGONIST"
      },
      "roos": {
        "type": "CHARACTER",
        "name": "Roos Jones",
        "aliases": [
          "Roos"
        ],
        "source": "FIXED_PROTAGONIST"
      },
      "estate": {
        "type": "LOCATION",
        "name": "L'Horizon",
        "aliases": [],
        "source": "MANUAL"
      },
      "beach-house": {
        "type": "LOCATION",
        "name": "Beach house",
        "aliases": [],
        "source": "MANUAL"
      },
      "terrace": {
        "type": "LOCATION",
        "name": "Terrace",
        "aliases": [],
        "source": "MANUAL"
      },
      "mug": {
        "type": "ITEM",
        "name": "Valerie’s mug",
        "aliases": [],
        "source": "ASSISTANT_TURN"
      },
      "singing": {
        "type": "SKILL",
        "name": "Singing",
        "aliases": [],
        "source": "MANUAL"
      }
    },
    "facts": [
      {
        "id": "valerie-mug",
        "subject": "valerie",
        "predicate": "POSSESSES",
        "object": "mug",
        "polarity": "POSITIVE",
        "status": "ACTIVE",
        "source": "ASSISTANT_TURN",
        "sourceTurn": 1,
        "hard": false
      },
      {
        "id": "valerie-singing",
        "subject": "valerie",
        "predicate": "CAN_PERFORM",
        "object": "singing",
        "polarity": "POSITIVE",
        "status": "ACTIVE",
        "source": "FIXED_PROTAGONIST",
        "sourceTurn": null,
        "hard": true
      },
      {
        "id": "merel-protective",
        "subject": "merel",
        "predicate": "PROTECTIVE_OF",
        "object": "valerie",
        "polarity": "POSITIVE",
        "status": "ACTIVE",
        "source": "FIXED_PROTAGONIST",
        "sourceTurn": null,
        "hard": true
      },
      {
        "id": "roos-valerie",
        "subject": "roos",
        "predicate": "LIVES_WITH",
        "object": "valerie",
        "polarity": "POSITIVE",
        "status": "ACTIVE",
        "source": "FIXED_PROTAGONIST",
        "sourceTurn": null,
        "hard": true
      }
    ]
  },
  "predicates": {
    "POSSESSES": {
      "subjectType": "CHARACTER",
      "objectType": "ITEM",
      "temporal": true,
      "positiveText": "possesses",
      "negativeText": "does not possess"
    },
    "WEARS": {
      "subjectType": "CHARACTER",
      "objectType": "ITEM",
      "temporal": true,
      "positiveText": "wears",
      "negativeText": "does not wear"
    },
    "CAN_PERFORM": {
      "subjectType": "CHARACTER",
      "objectType": "SKILL",
      "temporal": false,
      "positiveText": "can perform",
      "negativeText": "cannot perform"
    },
    "LOVES": {
      "subjectType": "CHARACTER",
      "objectType": "CHARACTER",
      "temporal": false,
      "positiveText": "loves",
      "negativeText": "does not love"
    },
    "TRUSTS": {
      "subjectType": "CHARACTER",
      "objectType": "CHARACTER",
      "temporal": false,
      "positiveText": "trusts",
      "negativeText": "does not trust"
    },
    "HATES": {
      "subjectType": "CHARACTER",
      "objectType": "CHARACTER",
      "temporal": false,
      "positiveText": "hates",
      "negativeText": "does not hate"
    },
    "PROTECTIVE_OF": {
      "subjectType": "CHARACTER",
      "objectType": "CHARACTER",
      "temporal": false,
      "positiveText": "is protective of",
      "negativeText": "is not protective of"
    },
    "FRIENDS_WITH": {
      "subjectType": "CHARACTER",
      "objectType": "CHARACTER",
      "temporal": false,
      "positiveText": "is friends with",
      "negativeText": "is not friends with"
    },
    "TRAINS_WITH": {
      "subjectType": "CHARACTER",
      "objectType": "CHARACTER",
      "temporal": false,
      "positiveText": "trains with",
      "negativeText": "does not train with"
    },
    "FEELS_SAFE_WITH": {
      "subjectType": "CHARACTER",
      "objectType": "CHARACTER",
      "temporal": false,
      "positiveText": "feels safe with",
      "negativeText": "does not feel safe with"
    },
    "LIVES_WITH": {
      "subjectType": "CHARACTER",
      "objectType": "CHARACTER",
      "temporal": true,
      "positiveText": "lives with",
      "negativeText": "does not live with"
    },
    "LIVES": {
      "subjectType": "CHARACTER",
      "objectType": "LOCATION",
      "temporal": true,
      "positiveText": "lives at",
      "negativeText": "does not live at"
    }
  }
};
