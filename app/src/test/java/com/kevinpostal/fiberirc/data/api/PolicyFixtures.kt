package com.kevinpostal.fiberirc.data.api

/**
 * A `NotificationPolicyView` as the gateway writes it (`notify_rest.d` `policyView`, with `X-IRCFiber-Device`), at
 * [rev]; this install's ref is `a1b2c3` and it overrides `dm.sound` off.
 */
fun policyViewJson(rev: Long = 12, preset: String = "focused", allMobile: Boolean = false): String = """
{"policy":{"v":2,"rev":$rev,
  "matrix":{"dm":{"desktop":true,"sound":true,"mobile":true,"email":false},
            "mention":{"desktop":true,"sound":true,"mobile":true,"email":false},
            "all":{"desktop":true,"sound":false,"mobile":$allMobile,"email":false},
            "invite":{"desktop":true,"sound":false,"mobile":true,"email":false},
            "notice":{"desktop":false,"sound":false,"mobile":false,"email":false},
            "wallops":{"desktop":false,"sound":false,"mobile":false,"email":false}},
  "snoozeUntil":0,
  "quiet":{"enabled":false,"tz":"","tzMode":"account",
           "windows":[{"days":[0,1,2,3,4,5,6],"start":"22:00","end":"08:00"}],
           "breakthrough":{"vip":[],"repeatSec":180,"urgentWords":true},"summary":true},
  "highlight":{"words":[{"w":"outage","urgent":true},{"w":"deploy","urgent":false}],"wholeWord":true,"caseSensitive":false},
  "presentation":{"preview":"full","coalesceSec":30,"autoDismiss":true},
  "email":{"mode":"delay15","dailyHour":8},
  "networks":{},
  "clients":{"a1b2c3":{"cells":{"dm.sound":false}}},
  "migratedAt":0,"bannerDismissed":false},
 "rev":$rev,"prefVersion":345,"preset":"$preset",
 "presets":{
   "everything":{"dm":{"desktop":true,"sound":true,"mobile":true},"mention":{"desktop":true,"sound":true,"mobile":true},
     "all":{"desktop":true,"sound":true,"mobile":true},"invite":{"desktop":true,"sound":true,"mobile":true},
     "notice":{"desktop":true,"sound":true,"mobile":true},"wallops":{"desktop":true,"sound":false,"mobile":true}},
   "focused":{"dm":{"desktop":true,"sound":true,"mobile":true},"mention":{"desktop":true,"sound":true,"mobile":true},
     "all":{"desktop":true,"sound":false,"mobile":false},"invite":{"desktop":true,"sound":false,"mobile":true},
     "notice":{"desktop":false,"sound":false,"mobile":false},"wallops":{"desktop":false,"sound":false,"mobile":false}},
   "quiet":{"dm":{"desktop":true,"sound":false,"mobile":true},"mention":{"desktop":true,"sound":false,"mobile":false},
     "all":{"desktop":false,"sound":false,"mobile":false},"invite":{"desktop":false,"sound":false,"mobile":false},
     "notice":{"desktop":false,"sound":false,"mobile":false},"wallops":{"desktop":false,"sound":false,"mobile":false}}},
 "quietTemplates":{
   "nights":[{"days":[0,1,2,3,4,5,6],"start":"22:00","end":"08:00"}],
   "nightsWeekends":[{"days":[0,1,2,3,6],"start":"22:00","end":"08:00"},{"days":[4],"start":"22:00","end":"24:00"},{"days":[5,6],"start":"00:00","end":"24:00"}],
   "offHours":[{"days":[0,1,2,3,6],"start":"18:00","end":"09:00"},{"days":[4],"start":"18:00","end":"24:00"},{"days":[5,6],"start":"00:00","end":"24:00"}]},
 "clientsMeta":[{"ref":"web","kind":"web","label":"Web browsers"},
   {"ref":"a1b2c3","kind":"android","label":"Pixel 8","appVersion":"1.2.0 (5)","lastSeenAt":1761900000000,"push":true,"tz":"Europe/Berlin"}],
 "status":{"snoozedUntil":0,"quietNow":false,"quietEndsAt":0,"nextQuietStartsAt":0},
 "capabilities":{"webPush":null,"email":{"available":true,"address":"k•••@ircfiber.com"}},
 "selfRef":"a1b2c3",
 "effective":{"snoozeUntil":0,"quietNow":false,"quietEndsAt":0,
   "matrix":{"dm":{"mobile":true,"sound":false},"mention":{"mobile":true,"sound":true},"all":{"mobile":$allMobile,"sound":false},
             "invite":{"mobile":true,"sound":false},"notice":{"mobile":false,"sound":false},"wallops":{"mobile":false,"sound":false}}}}
""".trimIndent()

fun policyView(rev: Long = 12, preset: String = "focused", allMobile: Boolean = false): NotificationPolicyView =
    SiteJson.decodeFromString(NotificationPolicyView.serializer(), policyViewJson(rev, preset, allMobile))
