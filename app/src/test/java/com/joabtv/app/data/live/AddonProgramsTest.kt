package com.joabtv.app.data.live

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class AddonProgramsTest {
    @Test fun descriptionRolloverAndNativeDates() {
        val now = Calendar.getInstance().apply { set(2026, 10, 22, 23, 56, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val p = LiveAddonParser.programs(JSONObject().put("description", "🔴 AGORA (23:30 – 00:15): Atual\nResumo\n⏭️ 00:15 – 01:00 · Próximo"), now)
        assertEquals(2, p.size)
        assertTrue(p[0].isNow(now))
        assertEquals(p[0].stop, p[1].start)
        val native = LiveAddonParser.programs(JSONObject("""{"videos":[{"title":"Nativo","startTime":"2026-11-22T20:00:00Z","endTime":"2026-11-22T21:00:00Z"}]}"""), now)
        assertEquals(1, native.size)
        assertEquals(3600000L, native[0].stop - native[0].start)
        assertTrue(LiveAddonParser.programs(JSONObject().put("description", "Sem horários"), now).isEmpty())
    }
}
