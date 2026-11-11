/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.emech.mp1802emulator;

import java.util.ArrayList;
import java.util.List;

public class EmulatorProfile {
    public String sectionId;
    public String name;
    public String hardwarePlatform;
    public String hexFile;
    public String releaseDate;
    public String retireDate;
    public String sportType;
    public int optionJumpers;
    public String keypadLayout;
    public String handheldMainTimer;
    public String handheldAux;
    public String protocolProfile;
    public List<String> scoreboardModels = new ArrayList<>();

    @Override
    public String toString() {
        return name + " (" + hexFile + ")";
    }
}
