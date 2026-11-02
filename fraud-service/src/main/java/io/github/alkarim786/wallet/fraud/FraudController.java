package io.github.alkarim786.wallet.fraud;

import io.github.alkarim786.wallet.fraud.FraudModel.Request;
import io.github.alkarim786.wallet.fraud.FraudModel.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/fraud")
public class FraudController {
    private final FraudEngine engine;

    public FraudController(FraudEngine engine) {
        this.engine = engine;
    }

    @PostMapping("/check")
    public Result check(@Valid @RequestBody Request request) {
        return engine.evaluate(request);
    }
}
