package com.yeah.yeahaiagent.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class TransitOptions {

    private TransitModeOption walking;

    private TransitModeOption driving;

    private TransitModeOption transit;

    /** null / true 表示可选驾车；酒吧及后续节点为 false。 */
    private Boolean drivingAllowed;

    public TransitOptions(TransitModeOption walking, TransitModeOption driving, TransitModeOption transit) {
        this.walking = walking;
        this.driving = driving;
        this.transit = transit;
        this.drivingAllowed = true;
    }

    public TransitOptions(TransitModeOption walking, TransitModeOption driving, TransitModeOption transit,
                          Boolean drivingAllowed) {
        this.walking = walking;
        this.driving = driving;
        this.transit = transit;
        this.drivingAllowed = drivingAllowed;
    }

    public boolean drivingEnabled() {
        return drivingAllowed == null || drivingAllowed;
    }
}
