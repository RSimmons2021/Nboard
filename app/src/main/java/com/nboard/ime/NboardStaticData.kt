package com.nboard.ime

val DEFAULT_TOP_EMOJIS = listOf("😀", "😂", "❤️", "🔥", "😭", "👍", "🥳", "✨", "😊", "🙏", "😍", "😅", "💯", "👀", "🤔", "🎉")
val EMOJI_SCAN_RANGES = listOf(
        0x203C..0x3299,
        0x1F000..0x1FAFF
    )
val KEYCAP_EMOJIS = listOf(
        "#️⃣", "*️⃣", "0️⃣", "1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣", "6️⃣", "7️⃣", "8️⃣", "9️⃣"
    )

val AZERTY_ROW_1 = listOf("a", "z", "e", "r", "t", "y", "u", "i", "o", "p")
val AZERTY_ROW_2 = listOf("q", "s", "d", "f", "g", "h", "j", "k", "l", "m")
val AZERTY_ROW_3 = listOf("w", "x", "c", "v", "b", "n", ",")
val GBOARD_AZERTY_ROW_3 = listOf("w", "x", "c", "v", "b", "n", "'")

val QWERTY_ROW_1 = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
val QWERTY_ROW_2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
val QWERTY_ROW_3 = listOf("z", "x", "c", "v", "b", "n", "m", ",", "'")
val GBOARD_QWERTY_ROW_2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
val GBOARD_QWERTY_ROW_3 = listOf("z", "x", "c", "v", "b", "n", "m", "'")

val VARIANT_MAP = mapOf(
        "a" to listOf("à", "á", "â", "ä", "æ", "ã", "å", "ā", "ą"),
        "e" to listOf("é", "è", "ê", "ë", "ē", "ė", "ę", "€"),
        "i" to listOf("ì", "í", "î", "ï", "ī", "į", "ı"),
        "o" to listOf("ò", "ó", "ô", "ö", "œ", "õ", "ø", "ō", "ő"),
        "u" to listOf("ù", "ú", "û", "ü", "ū", "ů", "ű", "ų"),
        "c" to listOf("ç", "ć", "č"),
        "s" to listOf("ß", "ś", "š", "ş"),
        "z" to listOf("ž", "ź", "ż"),
        "l" to listOf("ł", "ľ", "ĺ"),
        "r" to listOf("ř", "ŕ"),
        "d" to listOf("ď", "đ", "ð"),
        "t" to listOf("ť", "ţ", "þ"),
        "g" to listOf("ğ", "ģ", "ǧ"),
        "h" to listOf("ħ", "ĥ"),
        "j" to listOf("ĵ"),
        "k" to listOf("ķ", "ĸ"),
        "w" to listOf("ŵ", "ẁ", "ẃ", "ẅ"),
        "n" to listOf("ñ", "ń", "ň", "ņ"),
        "y" to listOf("ÿ", "ý"),
        "'" to listOf("’", "ʼ", "`", "´"),
        "\"" to listOf("«", "»", "“", "”"),
        "." to listOf("!", "?", ";", "…", "•", "·"),
        "," to listOf(".", ";", ":", "…", "!", "?", "'"),
        "$" to listOf("€", "£", "¥", "₹", "₩", "¢"),
        "-" to listOf("-", "–", "—", "•")
    )

val SMART_TYPING_SENTENCE_ENDERS = setOf('.', '!', '?')
val AUTOCORRECT_TRIGGER_DELIMITERS = setOf(' ', '.', ',', '!', '?', ';', ':', '\n')
val PREDICTION_NO_SPACE_BEFORE = setOf('.', ',', '!', '?', ';', ':', ')', ']', '}', '"', '”', '\n')
const val PREDICTION_WORD_TAIL_WINDOW = 48
const val ADDRESS_CONTEXT_WINDOW = 40
val ADDRESS_SEPARATORS = setOf('.', '@', '/', '#', '_')
val AUTOCORRECT_CLOSING_PUNCTUATION = setOf(')', ']', '}', '"', '”', '»', '*', '_')
val APOSTROPHE_CHARS = setOf('\'', '’', '‘', 'ʼ', '`', '´', '‛', '＇')
val VOWELS_FOR_REPEAT = setOf('a', 'e', 'i', 'o', 'u', 'y')
val DIACRITIC_REGEX = Regex("\\p{M}+")
val ASSET_WORD_REGEX = Regex("[a-zàâäéèêëîïôöùûüçœæÿ]+(?:['-][a-zàâäéèêëîïôöùûüçœæÿ]+)*")
val WORD_TOKEN_REGEX = Regex("[\\p{L}][\\p{L}'’‘ʼ`´‛＇\\-]*")
val WHITESPACE_REGEX = Regex("\\s+")

val ENGLISH_WORDS = setOf(
        "a","about","after","again","all","also","always","am","an","and","any","are","around","as","at",
        "back","be","because","been","before","being","best","better","both","but","by","can","could",
        "day","did","do","does","doing","done","dont","down","each","even","every","for","from","get","go",
        "good","great","had","has","have","he","hello","help","her","here","him","his","how","i","if","in",
        "into","is","it","its","just","know","language","last","let","like","little","long","look","make",
        "many","me","more","most","much","my","need","new","next","no","not","now","of","on","one","only",
        "or","other","our","out","over","people","please","right","same","say","see","she","should","small",
        "so","some","something","start","still","such","take","text","than","thank","that","the","their",
        "them","then","there","these","they","thing","this","time","to","today","too","try","two","up","us",
        "use","very","want","was","way","we","well","were","what","when","where","which","who","why","will",
        "with","word","work","would","write","yes","you","your","yours"
    )

val FRENCH_WORDS = setOf(
        "a","à","abord","afin","ai","aie","ainsi","alors","apres","après","au","aucun","aussi","autre","aux",
        "avoir","avec","beaucoup","bien","bon","bonjour","car","ce","cela","celle","celui","ces","cet","cette",
        "chaque","chez","comme","comment","dans","de","des","deux","devant","donc","du","elle","elles","en",
        "encore","entre","est","et","ete","été","etre","être","fait","faire","faut","grand","gros","ici","il",
        "ils","je","jour","juste","la","le","les","leur","leurs","lui","ma","mais","me","mes","mieux","moins",
        "mon","mot","mots","ne","ni","non","nos","notre","nous","nouveau","ou","où","par","parce","pas","peu",
        "plus","pour","pourquoi","premier","quand","que","quel","quelle","quelles","quels","qui","quoi","sa",
        "sans","se","ses","si","son","sont","sur","ta","te","tes","text","texte","tes","toi","ton","toujours",
        "tout","tous","tres","très","tu","un","une","votre","vous","vu","y","salut",
        "fleur","fleurs","fleurir","jolie","magnifique","maison","chat","chien","amour","merci"
    )

val FRENCH_DEFAULT_PREDICTIONS = listOf(
        "je", "vous", "nous", "le", "la", "de", "et", "pour", "avec", "dans"
    )
val ENGLISH_DEFAULT_PREDICTIONS = listOf(
        "i", "you", "we", "the", "to", "and", "for", "with", "in", "on"
    )
val MIXED_DEFAULT_PREDICTIONS = listOf(
        "je", "i", "vous", "you", "le", "the", "de", "to", "et", "and"
    )

val FRENCH_CONTEXT_HINTS = mapOf(
        "*" to listOf("je", "vous", "le", "la", "de", "et", "pour"),
        "je" to listOf("suis", "vais", "peux", "veux", "ne"),
        "tu" to listOf("es", "vas", "peux", "veux", "as"),
        "il" to listOf("est", "a", "va", "peut", "fait"),
        "elle" to listOf("est", "a", "va", "peut", "fait"),
        "nous" to listOf("sommes", "avons", "allons", "pouvons", "voulons"),
        "vous" to listOf("etes", "avez", "allez", "pouvez", "voulez"),
        "ils" to listOf("sont", "ont", "vont", "peuvent", "font"),
        "elles" to listOf("sont", "ont", "vont", "peuvent", "font"),
        "c'est" to listOf("important", "possible", "vrai", "bien", "la"),
        "de" to listOf("la", "le", "l'", "mon", "ton", "notre"),
        "des" to listOf("gens", "choses", "mots", "fleurs", "idées"),
        "un" to listOf("peu", "jour", "mot", "chat", "chien"),
        "une" to listOf("fois", "phrase", "idee", "maison", "fleur"),
        "le" to listOf("temps", "texte", "chat", "monde", "moment"),
        "la" to listOf("vie", "maison", "phrase", "fleur", "question"),
        "les" to listOf("gens", "mots", "choses", "jours", "fleurs"),
        "dans" to listOf("le", "la", "les", "un", "une"),
        "pour" to listOf("le", "la", "vous", "nous", "faire"),
        "je suis" to listOf("en", "a", "avec", "d'accord", "pret"),
        "il est" to listOf("important", "possible", "temps", "la", "vraiment"),
        "nous sommes" to listOf("en", "la", "pret", "ici", "ensemble"),
        "vous etes" to listOf("en", "la", "pret", "ici", "sur")
    )

val ENGLISH_CONTEXT_HINTS = mapOf(
        "*" to listOf("i", "you", "the", "to", "and", "for", "with"),
        "i" to listOf("am", "have", "will", "can", "need"),
        "you" to listOf("are", "have", "can", "will", "should"),
        "he" to listOf("is", "has", "will", "can", "was"),
        "she" to listOf("is", "has", "will", "can", "was"),
        "we" to listOf("are", "have", "can", "will", "need"),
        "they" to listOf("are", "have", "can", "will", "were"),
        "the" to listOf("best", "same", "text", "time", "way"),
        "to" to listOf("the", "be", "do", "go", "make"),
        "for" to listOf("the", "you", "me", "this", "that"),
        "in" to listOf("the", "a", "this", "my", "your"),
        "on" to listOf("the", "my", "your", "this", "that"),
        "a" to listOf("new", "good", "little", "great", "small"),
        "an" to listOf("idea", "example", "email", "issue", "answer"),
        "i am" to listOf("going", "not", "ready", "here", "sure"),
        "i have" to listOf("to", "a", "been", "no", "the"),
        "you are" to listOf("the", "not", "going", "right", "welcome"),
        "we are" to listOf("going", "not", "ready", "here", "the")
    )

val FRENCH_TYPOS = mapOf(
        "salot" to "salut",
        "bjr" to "bonjour",
        "stp" to "s'il te plaît",
        "svp" to "s'il vous plaît",
        "cest" to "c'est",
        "cetait" to "c'était",
        "cetaient" to "c'étaient",
        "jaime" to "j'aime",
        "jai" to "j'ai",
        "jaii" to "j'ai",
        "jaurai" to "j'aurai",
        "jaurais" to "j'aurais",
        "jetais" to "j'étais",
        "quil" to "qu'il",
        "quon" to "qu'on",
        "daccord" to "d'accord",
        "lheure" to "l'heure",
        "lamour" to "l'amour",
        "aujourdhui" to "aujourd'hui",
        "presquile" to "presqu'île"
    )

/** Texting words absent from the word lists; typed on purpose, never "corrected". */
val TEXTING_VOCABULARY = setOf(
    "lmao", "lmfao", "rofl", "smh", "tbh", "imo", "imho", "idk", "idc", "ngl", "istg", "irl", "ikr", "fyi",
    "btw", "brb", "omw", "wyd", "hbu", "tbf", "afaik", "jk", "nvm", "pls", "plz", "thx", "ty", "tysm", "np",
    "bruh", "bro", "sis", "sus", "yeet", "lowkey", "highkey", "finna", "bestie", "periodt", "slay", "vibe",
    "vibes", "rizz", "simp", "stan", "deadass", "fr", "frfr", "rn", "atm", "tho", "tmrw", "yall", "y'all",
    "insta", "snapchat", "tiktok", "venmo", "lyft", "uber", "whatsapp", "facetime", "spotify", "netflix",
    "okayyy", "sooo", "yesss", "nooo", "omgg", "hahaha", "hahah", "lolol"
)

val ENGLISH_TYPOS = mapOf(
        "teh" to "the",
        "woudl" to "would",
        "dont" to "don't",
        "cant" to "can't",
        "wont" to "won't",
        "isnt" to "isn't",
        "arent" to "aren't",
        "wasnt" to "wasn't",
        "werent" to "weren't",
        "hasnt" to "hasn't",
        "havent" to "haven't",
        "hadnt" to "hadn't",
        "didnt" to "didn't",
        "doesnt" to "doesn't",
        "couldnt" to "couldn't",
        "wouldnt" to "wouldn't",
        "shouldnt" to "shouldn't",
        "mustnt" to "mustn't",
        "im" to "i'm",
        "ive" to "i've",
        "youre" to "you're",
        "youve" to "you've",
        "youll" to "you'll",
        "youd" to "you'd",
        "theyre" to "they're",
        "theyve" to "they've",
        "theyll" to "they'll",
        "weve" to "we've",
        "whats" to "what's",
        "thats" to "that's",
        "heres" to "here's",
        "theres" to "there's",
        // Well-known misspellings that the word lists contain as if valid (e.g. "untill").
        "untill" to "until",
        "wierd" to "weird",
        "begining" to "beginning",
        "seperate" to "separate",
        "seperately" to "separately",
        "tommorow" to "tomorrow",
        "tommorrow" to "tomorrow",
        "tomorow" to "tomorrow",
        "realy" to "really",
        "rember" to "remember",
        "remeber" to "remember",
        "freind" to "friend",
        "freinds" to "friends",
        "thier" to "their",
        "wich" to "which",
        "occured" to "occurred",
        "recieve" to "receive",
        "recieved" to "received",
        "beleive" to "believe",
        "belive" to "believe",
        "definately" to "definitely",
        "definatly" to "definitely",
        "goverment" to "government",
        "enviroment" to "environment",
        "truely" to "truly",
        "neccessary" to "necessary",
        "existance" to "existence",
        "appartment" to "apartment",
        "affraid" to "afraid",
        "everthing" to "everything",
        "sory" to "sorry",
        "youself" to "yourself",
        "accross" to "across",
        "adress" to "address",
        "arguement" to "argument",
        "basicly" to "basically",
        "calender" to "calendar",
        "comming" to "coming",
        "completly" to "completely",
        "dissapoint" to "disappoint",
        "embarass" to "embarrass",
        "finaly" to "finally",
        "foward" to "forward",
        "futher" to "further",
        "gaurd" to "guard",
        "happend" to "happened",
        "immediatly" to "immediately",
        "independant" to "independent",
        "knowlege" to "knowledge",
        "liek" to "like",
        "occassion" to "occasion",
        "persue" to "pursue",
        "posession" to "possession",
        "publically" to "publicly",
        "recomend" to "recommend",
        "sucess" to "success",
        "suprise" to "surprise",
        "tounge" to "tongue",
        "writting" to "writing",
        "probaly" to "probably",
        "beautifull" to "beautiful",
        "untll" to "until"
    )

val ALL_EMOJIS = listOf(
        "😀","😃","😄","😁","😆","😅","🤣","😂","🙂","🙃","😉","😊","😇","🥰","😍","🤩","😘","😗","😚","😙",
        "😋","😛","😜","🤪","😝","🤑","🤗","🤭","🫢","🤫","🤔","🫡","🤐","🤨","😐","😑","😶","🫥","😶‍🌫️","😏",
        "😒","🙄","😬","😮‍💨","🤥","😌","😔","😪","🤤","😴","😷","🤒","🤕","🤢","🤮","🤧","🥵","🥶","🥴","😵",
        "🤯","🤠","🥳","🥸","😎","🤓","🧐","😕","🫤","😟","🙁","☹️","😮","😯","😲","😳","🥺","🥹","😦","😧",
        "😨","😰","😥","😢","😭","😱","😖","😣","😞","😓","😩","😫","🥱","😤","😡","😠","🤬","😈","👿","💀",
        "☠️","💩","🤡","👹","👺","👻","👽","👾","🤖","😺","😸","😹","😻","😼","😽","🙀","😿","😾",
        "🙈","🙉","🙊","💋","💌","💘","💝","💖","💗","💓","💞","💕","💟","❣️","💔","❤️","🧡","💛","💚","💙",
        "💜","🤎","🖤","🤍","💯","💢","💥","💫","💦","💨","🕳️","💣","💬","🗨️","🗯️","💭","💤",
        "👋","🤚","🖐️","✋","🖖","🫱","🫲","🫳","🫴","👌","🤌","🤏","✌️","🤞","🫰","🤟","🤘","🤙","👈","👉",
        "👆","🖕","👇","☝️","🫵","👍","👎","✊","👊","🤛","🤜","👏","🙌","🫶","👐","🤲","🤝","🙏","✍️","💅",
        "🤳","💪","🦾","🦿","🦵","🦶","👂","🦻","👃","🧠","🫀","🫁","🦷","🦴","👀","👁️","👅","👄",
        "👶","🧒","👦","👧","🧑","👱","👨","🧔","🧔‍♂️","🧔‍♀️","👨‍🦰","👨‍🦱","👨‍🦳","👨‍🦲","👩","👩‍🦰","👩‍🦱","👩‍🦳","👩‍🦲","🧓",
        "👴","👵","🙍","🙍‍♂️","🙍‍♀️","🙎","🙎‍♂️","🙎‍♀️","🙅","🙅‍♂️","🙅‍♀️","🙆","🙆‍♂️","🙆‍♀️","💁","💁‍♂️","💁‍♀️","🙋","🙋‍♂️","🙋‍♀️",
        "🧏","🧏‍♂️","🧏‍♀️","🙇","🙇‍♂️","🙇‍♀️","🤦","🤦‍♂️","🤦‍♀️","🤷","🤷‍♂️","🤷‍♀️","👨‍⚕️","👩‍⚕️","👨‍🎓","👩‍🎓","👨‍🏫","👩‍🏫","👨‍💻","👩‍💻",
        "👨‍🔧","👩‍🔧","👨‍🍳","👩‍🍳","👨‍🚀","👩‍🚀","👨‍⚖️","👩‍⚖️","👮","👮‍♂️","👮‍♀️","🕵️","🕵️‍♂️","🕵️‍♀️","💂","💂‍♂️","💂‍♀️","🥷","👷","👷‍♂️",
        "👷‍♀️","👸","🤴","👳","👳‍♂️","👳‍♀️","👲","🧕","🤵","🤵‍♂️","🤵‍♀️","👰","👰‍♂️","👰‍♀️","🤰","🫃","🫄","🤱","👩‍🍼","👨‍🍼",
        "🎉","🎊","🎈","🎁","🎂","🍰","🧁","🍾","🥂","🍻","🍺","🍷","🥃","🍸","🍹","🧉","☕","🫖","🍫","🍿",
        "🍎","🍊","🍋","🍌","🍉","🍇","🍓","🫐","🍒","🍑","🥭","🍍","🥥","🥝","🍅","🍆","🥑","🥦","🥬","🥒",
        "🌶️","🫑","🌽","🥕","🫒","🧄","🧅","🥔","🍠","🥐","🥯","🍞","🥖","🥨","🧀","🥚","🍳","🥓","🥩","🍗",
        "🍖","🌭","🍔","🍟","🍕","🌮","🌯","🥙","🧆","🥪","🌭","🍜","🍝","🍣","🍱","🍛","🍤","🍙","🍚","🍘",
        "⚽","🏀","🏈","⚾","🥎","🎾","🏐","🏉","🥏","🎱","🏓","🏸","🥅","🏒","🏑","🥍","🏏","🪃","🥊","🥋",
        "🎮","🕹️","🎲","♟️","🧩","🎯","🎳","🎭","🎨","🎬","🎤","🎧","🎼","🎹","🥁","🎸","🎻","🎺","🪗","🎷",
        "🚗","🚕","🚙","🚌","🚎","🏎️","🚓","🚑","🚒","🚐","🚚","🚛","🚜","🏍️","🛵","🚲","🛴","🚨","✈️","🛫",
        "🛬","🚀","🛸","🚁","⛵","🚤","🛥️","🚢","⛴️","🚂","🚆","🚇","🚝","🚟","🚡","🚠","🗽","🗼","🗿","🗺️",
        "🌍","🌎","🌏","🗻","🏕️","🏖️","🏜️","🏝️","🏛️","🏟️","🏠","🏡","🏢","🏣","🏤","🏥","🏦","🏨","🏪","🏫",
        "⌚","📱","💻","⌨️","🖥️","🖨️","🖱️","🧮","📷","📸","📹","🎥","📞","☎️","📟","📠","📺","📻","🎙️","🔋",
        "🔌","💡","🔦","🕯️","🧯","🧲","💸","💰","💳","🪙","💎","🔑","🗝️","🔒","🔓","🔐","🧰","🛠️","⚙️","🔧",
        "✅","☑️","✔️","❌","❎","➕","➖","➗","✖️","♾️","‼️","⁉️","❓","❔","❕","❗","〰️","➰","➿","⭕",
        "🌟","✨","⚡","🔥","💧","🌈","☀️","🌤️","⛅","☁️","🌧️","⛈️","🌩️","❄️","☃️","🌊","🍀","🌸","🌹","🌻"
    )

val EMOJI_KEYWORDS = mapOf(
        "😀" to "grin happy smile",
        "😂" to "laugh lol",
        "😭" to "cry tears",
        "❤️" to "heart love",
        "🔥" to "fire hot",
        "👍" to "thumbs up yes ok",
        "🙏" to "pray thanks please",
        "👏" to "clap applause",
        "🎉" to "party celebration",
        "✨" to "sparkles",
        "😡" to "angry mad",
        "🤔" to "thinking",
        "😴" to "sleep",
        "🥳" to "party hat",
        "🤝" to "handshake",
        "✅" to "check valid",
        "💡" to "idea",
        "⚽" to "football sport",
        "🍕" to "pizza food",
        "🍔" to "burger food",
        "🍟" to "fries food",
        "☕" to "coffee",
        "🚗" to "car",
        "✈️" to "plane travel",
        "🌧️" to "rain",
        "🌞" to "sun",
        "🌈" to "rainbow",
        "🧠" to "brain",
        "💻" to "computer",
        "📱" to "phone",
        "📸" to "camera",
        "💯" to "hundred perfect",
        "💸" to "money",
        "🕘" to "latest recent"
    )
