package io.coti.trustscore.rulesData;


import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement(name = "component")
public class Component {
    @XmlAttribute(name = "name")
    private String name;
    @XmlElement(name = "definition")
    private String definition;
    @XmlElement(name = "range")
    private Range range;
    @XmlElement(name = "weight")
    private double weight;
    @XmlElement(name = "decay")
    private String decay;
}