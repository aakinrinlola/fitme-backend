package com.mike.backend.myBackendTest.service;

import com.mike.backend.myBackendTest.dto.CreateUserRequest;
import com.mike.backend.myBackendTest.entity.AppUser;
import com.mike.backend.myBackendTest.exception.ResourceNotFoundException;
import com.mike.backend.myBackendTest.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public AppUser createUser(CreateUserRequest req) {
        AppUser user = new AppUser(req.username(), req.email());
        user.setAge(req.age());
        user.setWeightKg(req.weightKg());
        user.setHeightCm(req.heightCm());
        if (req.fitnessLevel() != null) {
            user.setFitnessLevel(req.fitnessLevel());
        }
        return userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public AppUser getUser(Long id) {
        return userRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("User nicht gefunden: " + id));
    }

    @Transactional(readOnly = true)
    public List<AppUser> getAllUsers() {
        return userRepository.findAll();
    }

    public AppUser updateUser(Long id, CreateUserRequest req) {
        AppUser user = getUser(id);
        user.setUsername(req.username());
        user.setEmail(req.email());
        user.setAge(req.age());
        user.setWeightKg(req.weightKg());
        user.setHeightCm(req.heightCm());
        if (req.fitnessLevel() != null) {
            user.setFitnessLevel(req.fitnessLevel());
        }
        return userRepository.save(user);
    }

    public void deleteUser(Long id) {
        if (!userRepository.existsById(id)) {
            throw new ResourceNotFoundException("User nicht gefunden: " + id);
        }
        userRepository.deleteById(id);
    }
}
