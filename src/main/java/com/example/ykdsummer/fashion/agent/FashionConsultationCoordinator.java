package com.example.ykdsummer.fashion.agent;

import com.example.ykdsummer.fashion.model.FashionRequest;
import com.example.ykdsummer.fashion.model.FashionResult;

public interface FashionConsultationCoordinator {

    FashionResult consult(FashionRequest request);
}
