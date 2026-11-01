package com.kevinpostal.fiberirc.ui.shell

import com.kevinpostal.fiberirc.data.irc.IrcMember
import com.kevinpostal.fiberirc.ui.theme.MemberCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class MemberSectionsTest {
    @Test
    fun groupsByHighestPrefixInBandOrderSkippingEmptyBands() {
        val members = listOf(
            IrcMember("q", "~"),
            IrcMember("o1", "@+"),
            IrcMember("o2", "@"),
            IrcMember("h", "%"),
            IrcMember("a", ""),
            IrcMember("b", ""),
        )
        val sections = memberSections(members)
        assertEquals(
            listOf(MemberCategory.OWNERS, MemberCategory.OPS, MemberCategory.HALFOPS, MemberCategory.MEMBERS),
            sections.map { it.category },
        )
        assertEquals(listOf("Owners", "Ops", "Half Ops", "Members"), sections.map { it.title })
        assertEquals(listOf("o1", "o2"), sections[1].members.map { it.nick })
        assertEquals(emptyList<Any>(), memberSections(emptyList()))
    }
}
