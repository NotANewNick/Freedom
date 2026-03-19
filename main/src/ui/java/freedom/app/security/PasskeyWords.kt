package freedom.app.security

/**
 * Vulgar and funny word pool for passphrase selection.
 *
 * Words are chosen to be memorable precisely because they are rude and absurd.
 * Diceware research shows that memorability is the single biggest factor in
 * whether people actually remember their passphrase — a phrase like
 * "bellend shart cockwomble pisswhistle" is unforgettable.
 *
 * Entropy with 4 words chosen in order: 238^4 ≈ 2^31.6
 * With 5 words: 238^5 ≈ 2^39.5  (recommended)
 * With 6 words: 238^6 ≈ 2^47.4  (very strong)
 */
object PasskeyWords {

    val words: List<String> = listOf(

        // ── Classic vulgar body parts ──────────────────────────────────────────
        "cock", "dick", "knob", "todger", "willy", "wang", "dong", "schlong",
        "bellend", "foreskin", "bollocks", "gonads", "scrotum", "testes",
        "vagina", "minge", "fanny", "flaps", "labia", "clitoris",
        "arsehole", "rectum", "sphincter", "taint", "crotch", "loins",
        "tit", "nipple", "boob", "buttocks", "bum", "crack", "pubes", "nethers",

        // ── Excretions and emissions ───────────────────────────────────────────
        "shit", "crap", "turd", "dump", "nugget", "skidmark", "shart",
        "fart", "trump", "ripper", "belch", "burp", "piss", "wee",
        "snot", "bogey", "phlegm", "loogie", "jizz", "spunk", "splooge",
        "queef", "discharge", "seepage", "dribble", "ooze", "squirt",

        // ── Vulgar actions ─────────────────────────────────────────────────────
        "shag", "bonk", "bang", "hump", "wank", "fondle", "grope",
        "spank", "thrust", "schtupp", "roger", "pork", "plough", "ride",

        // ── British slang insults (inherently funny) ───────────────────────────
        "wanker", "tosser", "bellend", "dickhead", "twat", "prick",
        "pillock", "numpty", "plonker", "muppet", "minger", "berk",
        "prat", "git", "twit", "nitwit", "gobshite", "dunderhead",
        "knobhead", "arsewipe", "bumface", "shitstain", "turdface",
        "spunktrumpet", "wazzock", "cockwomble",

        // ── Glorious compound words ────────────────────────────────────────────
        "twatwaffle", "jizztrumpet", "knobgoblin", "arsebiscuit",
        "fartcloud", "spunkmuffin", "dickwhistle", "bumhole",
        "arsecheeks", "knobjockey", "turdburgler", "cockmonger",
        "shitgibbon", "assclown", "pisswhistle", "fucktrumpet",
        "dickbiscuit", "ballsack", "scroteface", "cumguzzler",
        "turdnugget", "cuntwaffle", "arsemonger", "knobwhistle",
        "shitcanoe", "dicktickler", "thundercunt", "fucknugget",
        "twatknuckle", "buttplug", "rimjob", "gooch", "chode",

        // ── Funny toilet words ─────────────────────────────────────────────────
        "crapper", "shithouse", "dunny", "privy", "outhouse",
        "thunderbox", "latrine", "bog", "potty", "cubicle",

        // ── Funny vulgar sounds ────────────────────────────────────────────────
        "squelch", "splat", "plop", "toot", "gurgle", "slurp",
        "sploosh", "thwack", "splodge", "blorp", "schlorp",
        "squelchy", "farted", "burped", "sloshing",

        // ── Rude adjectives ────────────────────────────────────────────────────
        "crusty", "musty", "funky", "rank", "fetid", "putrid",
        "rancid", "pungent", "gamey", "whiffy", "pongy", "skanky",
        "grimy", "mucky", "filthy", "slimy", "greasy", "sweaty",
        "smeggy", "manky", "grotty", "scabby", "crusty", "skeevy",

        // ── Absurdly specific vulgar nouns ─────────────────────────────────────
        "ballsweat", "crotchrot", "skidmarks", "streaker", "flasher",
        "pervert", "creep", "lecher", "ogler", "horndog",
        "smegma", "hemorrhoid", "thrush", "chlamydia", "gonorrhoea",

        // ── Animals + vulgarity (inherently funny combo) ───────────────────────
        "cockerel", "titmouse", "booby", "shag", "asscrab",
        "crabs", "ringworm", "tapeworm", "dingleberry",

        // ── Funny euphemisms everyone knows ───────────────────────────────────
        "sausage", "cucumber", "banana", "love-trumpet", "man-meat",
        "lady-bits", "downstairs", "naughty", "jiggly", "wobbly",
        "throbbing", "thrusting", "gushing", "heaving", "pulsating"

    )

    /** Return a shuffled copy of the full word list. */
    fun shuffled(): List<String> = words.shuffled()
}
