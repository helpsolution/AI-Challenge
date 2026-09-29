package advent.rag.web

import advent.rag.eval.EvalReport
import advent.rag.eval.Evaluation
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/eval")
class EvalController(private val evaluation: Evaluation) {

    /** Задать контрольные вопросы в обоих режимах и записать отчёт. Занимает несколько минут. */
    @PostMapping
    fun run(): EvalReport = evaluation.run()
}
