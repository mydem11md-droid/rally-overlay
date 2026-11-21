package com.mythic.rally

data class RallyInput(val id:Int,val name:String,val marchSeconds:Int,val rallySeconds:Int=0) {
    val isRally get()=rallySeconds>0
    val totalSeconds get()=marchSeconds+rallySeconds
}
data class RallyPlanRow(val id:Int,val name:String,val isRally:Boolean,val rallySeconds:Int,val marchSeconds:Int,val actionAtMillis:Long,val departAtMillis:Long,val arrivalAtMillis:Long)
data class RallyPlan(val rows:List<RallyPlanRow>,val arrivalAtMillis:Long,val baseAtMillis:Long)

object RallyPlanner {
    fun planNow(nowMillis:Long,prepSeconds:Int,people:List<RallyInput>):RallyPlan {
        require(people.isNotEmpty())
        val base=nowMillis+prepSeconds.coerceAtLeast(0)*1000L
        val arrival=base+people.maxOf { it.totalSeconds }*1000L
        val rows=people.map { p ->
            val action=arrival-p.totalSeconds*1000L
            RallyPlanRow(p.id,p.name,p.isRally,p.rallySeconds,p.marchSeconds,action,arrival-p.marchSeconds*1000L,arrival)
        }.sortedBy { it.actionAtMillis }
        return RallyPlan(rows,arrival,base)
    }
    fun planTarget(targetMillis:Long,people:List<RallyInput>):Pair<RallyPlan,List<String>> {
        val errors=mutableListOf<String>()
        val rows=people.mapNotNull { p ->
            val action=targetMillis-p.totalSeconds*1000L
            if(action<System.currentTimeMillis()-1000L)errors.add(p.name)
            RallyPlanRow(p.id,p.name,p.isRally,p.rallySeconds,p.marchSeconds,action,targetMillis-p.marchSeconds*1000L,targetMillis)
        }.sortedBy { it.actionAtMillis }
        return RallyPlan(rows,targetMillis,targetMillis) to errors
    }
}
