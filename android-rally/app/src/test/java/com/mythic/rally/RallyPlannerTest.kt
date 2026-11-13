package com.mythic.rally

import org.junit.Assert.assertEquals
import org.junit.Test

class RallyPlannerTest {
    private fun ms(h:Int,m:Int,s:Int)=((h*3600L+m*60L+s)*1000L)
    @Test fun mixedRallyAndSoloAcceptance() {
        val p=RallyPlanner.planNow(ms(7,20,0),10,listOf(RallyInput(1,"Omar",40,300),RallyInput(2,"Mythic",30)))
        assertEquals(ms(7,20,10),p.rows.first{it.id==1}.actionAtMillis)
        assertEquals(ms(7,25,10),p.rows.first{it.id==1}.departAtMillis)
        assertEquals(ms(7,25,20),p.rows.first{it.id==2}.actionAtMillis)
        assertEquals(ms(7,25,50),p.arrivalAtMillis)
    }
    @Test fun fifteenMinuteRallyStartsAtBaseAndSoloFollowsByFourteenFiftyThree() {
        val p=RallyPlanner.planNow(ms(7,20,0),10,listOf(RallyInput(1,"Rally",43,900),RallyInput(2,"Solo",50)))
        assertEquals(ms(7,20,10),p.rows.first{it.id==1}.actionAtMillis)
        assertEquals(ms(7,35,53),p.rows.first{it.id==2}.actionAtMillis)
    }
    @Test fun twoRalliesHaveNineFortyActionGap() {
        val p=RallyPlanner.planNow(ms(7,20,0),10,listOf(RallyInput(1,"Five",40,300),RallyInput(2,"Fifteen",20,900)))
        assertEquals(ms(7,20,10),p.rows.first{it.id==2}.actionAtMillis)
        assertEquals(ms(7,29,50),p.rows.first{it.id==1}.actionAtMillis)
    }
}
