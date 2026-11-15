package com.security.service.alexa;

import com.security.dto.alexa.AlexaStoreInfoDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AlexaStoreInfoService {

    @Value("${store.name:#{null}}")
    private String name;

    @Value("${store.address:#{null}}")
    private String address;

    @Value("${store.phone:#{null}}")
    private String phone;

    @Value("${store.whatsapp:#{null}}")
    private String whatsapp;

    @Value("${store.facebook:#{null}}")
    private String facebook;

    @Value("${store.hours:#{null}}")
    private String hours;

    @Value("${store.paymentMethods:#{null}}")
    private String paymentMethods;

    @Value("${store.warrantyInfo:#{null}}")
    private String warrantyInfo;

    public AlexaStoreInfoDTO getStoreInfo() {
        return new AlexaStoreInfoDTO(
                name, address, phone, whatsapp, facebook, hours, paymentMethods, warrantyInfo
        );
    }
}
