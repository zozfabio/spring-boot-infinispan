package com.example.infinispan;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.Serializable;

/**
 * Created by zozfabio on 06/12/15.
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}

@Configuration
@EnableCaching
class CacheConfiguration {
}

class People implements Serializable {

    private String name;

    People(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}

@Service
class PeoplesService {

    @Cacheable(cacheNames = "mycache", key = "#root.method.name")
    public People getOne() {
        System.out.println("hit one");
        return new People("One");
    }

    @Cacheable(cacheNames = "mycache", key = "#root.method.name")
    public People getTwo() {
        System.out.println("hit two");
        return new People("Two");
    }

    @Cacheable(cacheNames = "mycache", key = "#root.method.name")
    public People getThree() {
        System.out.println("hit thre");
        return new People("Three");
    }
}

@RestController
@RequestMapping("/")
class PeoplesController {

    @Autowired
    private PeoplesService service;

    @RequestMapping("/one")
    private People one() {
        return service.getOne();
    }

    @RequestMapping("/two")
    private People two() {
        return service.getTwo();
    }

    @RequestMapping("/three")
    private People three() {
        return service.getThree();
    }
}
