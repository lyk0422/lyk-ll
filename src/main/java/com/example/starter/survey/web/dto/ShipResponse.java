package com.example.starter.survey.web.dto;

import com.example.starter.survey.domain.Ship;

/** 船舶视图。 */
public record ShipResponse(Long id, String imo, String name,
                           String annualAnniversary, String deliveryDate) {

    public static ShipResponse from(Ship ship) {
        return new ShipResponse(ship.id(), ship.imo(), ship.name(),
                ship.annualAnniversary().toString(), ship.deliveryDate().toString());
    }
}
