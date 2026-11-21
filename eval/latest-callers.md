| method | name-level files | resolved call sites | name-only |
| --- | --- | --- | --- |
| src/main/java/org/springframework/samples/petclinic/model/BaseEntity.java:39 getId | 7 | 8 |  |
| src/main/java/org/springframework/samples/petclinic/model/BaseEntity.java:43 setId | 8 | 8 |  |
| src/main/java/org/springframework/samples/petclinic/model/BaseEntity.java:47 isNew | 4 | 7 |  |
| src/main/java/org/springframework/samples/petclinic/model/NamedEntity.java:37 getName | 9 | 8 |  |
| src/main/java/org/springframework/samples/petclinic/model/NamedEntity.java:41 setName | 9 | 8 |  |
| src/main/java/org/springframework/samples/petclinic/model/NamedEntity.java:45 toString | 8 | 0 | yes |
| src/main/java/org/springframework/samples/petclinic/model/Person.java:41 getFirstName | 3 | 3 |  |
| src/main/java/org/springframework/samples/petclinic/model/Person.java:45 setFirstName | 6 | 8 |  |
| src/main/java/org/springframework/samples/petclinic/model/Person.java:49 getLastName | 5 | 8 |  |
| src/main/java/org/springframework/samples/petclinic/model/Person.java:53 setLastName | 6 | 8 |  |
| src/main/java/org/springframework/samples/petclinic/owner/Owner.java:72 getAddress | 1 | 0 | yes |
| src/main/java/org/springframework/samples/petclinic/owner/Owner.java:76 setAddress | 3 | 3 |  |
| src/main/java/org/springframework/samples/petclinic/owner/Owner.java:80 getCity | 1 | 0 | yes |
| src/main/java/org/springframework/samples/petclinic/owner/Owner.java:84 setCity | 3 | 3 |  |
| src/main/java/org/springframework/samples/petclinic/owner/Owner.java:88 getTelephone | 1 | 0 | yes |
| src/main/java/org/springframework/samples/petclinic/owner/Owner.java:92 setTelephone | 3 | 3 |  |
| src/main/java/org/springframework/samples/petclinic/owner/Owner.java:96 getPets | 4 | 8 |  |
| src/main/java/org/springframework/samples/petclinic/owner/Owner.java:100 addPet | 7 | 8 |  |
| src/main/java/org/springframework/samples/petclinic/owner/Owner.java:120 getPet | 6 | 5 |  |
| src/main/java/org/springframework/samples/petclinic/owner/Owner.java:129 getPet | 6 | 8 |  |
| src/main/java/org/springframework/samples/petclinic/owner/Owner.java:147 getPet | 6 | 3 |  |
| src/main/java/org/springframework/samples/petclinic/owner/Owner.java:159 toString | 8 | 0 | yes |
| src/main/java/org/springframework/samples/petclinic/owner/Owner.java:176 addVisit | 4 | 2 |  |
| src/main/java/org/springframework/samples/petclinic/owner/OwnerController.java:59 setAllowedFields | 2 | 0 | yes |
| src/main/java/org/springframework/samples/petclinic/owner/OwnerController.java:64 findOwner | 2 | 0 | yes |
| src/main/java/org/springframework/samples/petclinic/owner/OwnerController.java:72 initCreationForm | 4 | 0 | yes |
| src/main/java/org/springframework/samples/petclinic/owner/OwnerController.java:77 processCreationForm | 2 | 0 | yes |
| src/main/java/org/springframework/samples/petclinic/owner/OwnerController.java:89 initFindForm | 2 | 0 | yes |
| src/main/java/org/springframework/samples/petclinic/owner/OwnerController.java:94 processFindForm | 1 | 0 | yes |
| src/main/java/org/springframework/samples/petclinic/owner/OwnerController.java:132 addPaginationModel | 2 | 0 | yes |
| src/main/java/org/springframework/samples/petclinic/owner/OwnerController.java:141 findPaginatedForOwnersLastName | 1 | 1 |  |
| src/main/java/org/springframework/samples/petclinic/owner/OwnerController.java:148 initUpdateOwnerForm | 2 | 0 | yes |
| src/main/java/org/springframework/samples/petclinic/owner/OwnerController.java:153 processUpdateOwnerForm | 1 | 0 | yes |
| src/main/java/org/springframework/samples/petclinic/owner/OwnerController.java:178 showOwner | 2 | 0 | yes |
| src/main/java/org/springframework/samples/petclinic/owner/OwnerRepository.java:45 findByLastNameStartingWith | 4 | 0 | yes |
| src/main/java/org/springframework/samples/petclinic/owner/OwnerRepository.java:60 findById | 9 | 8 |  |
| src/main/java/org/springframework/samples/petclinic/owner/Pet.java:61 setBirthDate | 5 | 8 |  |
| src/main/java/org/springframework/samples/petclinic/owner/Pet.java:65 getBirthDate | 3 | 6 |  |
| src/main/java/org/springframework/samples/petclinic/owner/Pet.java:69 getType | 4 | 4 |  |
| src/main/java/org/springframework/samples/petclinic/owner/Pet.java:73 setType | 5 | 8 |  |
| src/main/java/org/springframework/samples/petclinic/owner/Pet.java:77 getVisits | 3 | 5 |  |
| src/main/java/org/springframework/samples/petclinic/owner/Pet.java:81 addVisit | 4 | 2 |  |
| src/main/java/org/springframework/samples/petclinic/owner/PetController.java:62 populatePetTypes | 1 | 0 | yes |
| src/main/java/org/springframework/samples/petclinic/owner/PetController.java:67 findOwner | 2 | 0 | yes |
| src/main/java/org/springframework/samples/petclinic/owner/PetController.java:75 findPet | 1 | 0 | yes |
| src/main/java/org/springframework/samples/petclinic/owner/PetController.java:89 initOwnerBinder | 1 | 0 | yes |
| src/main/java/org/springframework/samples/petclinic/owner/PetController.java:94 initPetBinder | 1 | 0 | yes |
| src/main/java/org/springframework/samples/petclinic/owner/PetController.java:100 initCreationForm | 4 | 0 | yes |
| src/main/java/org/springframework/samples/petclinic/owner/PetController.java:107 processCreationForm | 2 | 0 | yes |
| src/main/java/org/springframework/samples/petclinic/owner/PetController.java:139 initUpdateForm | 2 | 0 | yes |
| src/main/java/org/springframework/samples/petclinic/owner/PetController.java:144 processUpdateForm | 1 | 0 | yes |
| src/main/java/org/springframework/samples/petclinic/owner/PetController.java:186 updatePetDetails | 1 | 1 |  |
| src/main/java/org/springframework/samples/petclinic/owner/PetController.java:202 isDuplicatePetNameViolation | 1 | 0 | yes |
| src/main/java/org/springframework/samples/petclinic/owner/PetTypeFormatter.java:45 print | 2 | 1 |  |
| src/main/java/org/springframework/samples/petclinic/owner/PetTypeFormatter.java:51 parse | 4 | 2 |  |
| src/main/java/org/springframework/samples/petclinic/owner/PetTypeRepository.java:36 findPetTypes | 6 | 8 |  |
| src/main/java/org/springframework/samples/petclinic/owner/PetValidator.java:38 validate | 3 | 0 | yes |
| src/main/java/org/springframework/samples/petclinic/owner/PetValidator.java:64 supports | 2 | 2 |  |
| src/main/java/org/springframework/samples/petclinic/owner/Visit.java:54 getDate | 2 | 2 |  |
| src/main/java/org/springframework/samples/petclinic/owner/Visit.java:58 setDate | 2 | 1 |  |
| src/main/java/org/springframework/samples/petclinic/owner/Visit.java:62 getDescription | 1 | 0 | yes |
| src/main/java/org/springframework/samples/petclinic/owner/Visit.java:66 setDescription | 2 | 1 |  |
| src/main/java/org/springframework/samples/petclinic/owner/VisitController.java:51 setAllowedFields | 2 | 0 | yes |
| src/main/java/org/springframework/samples/petclinic/owner/VisitController.java:63 loadPetWithVisit | 1 | 0 | yes |
| src/main/java/org/springframework/samples/petclinic/owner/VisitController.java:83 minVisitDate | 1 | 0 | yes |
| src/main/java/org/springframework/samples/petclinic/owner/VisitController.java:90 initNewVisitForm | 2 | 0 | yes |
| src/main/java/org/springframework/samples/petclinic/owner/VisitController.java:97 processNewVisitForm | 1 | 0 | yes |
| src/main/java/org/springframework/samples/petclinic/PetClinicApplication.java:32 main | 4 | 0 | yes |
| src/main/java/org/springframework/samples/petclinic/PetClinicRuntimeHints.java:27 registerHints | 1 | 0 | yes |
| src/main/java/org/springframework/samples/petclinic/system/CacheConfiguration.java:35 petclinicCacheConfigurationCustomizer | 1 | 0 | yes |
| src/main/java/org/springframework/samples/petclinic/system/CacheConfiguration.java:49 cacheConfiguration | 1 | 1 |  |
| src/main/java/org/springframework/samples/petclinic/system/CrashController.java:31 triggerException | 2 | 1 |  |
| src/main/java/org/springframework/samples/petclinic/system/WebConfiguration.java:32 localeResolver | 1 | 0 | yes |
| src/main/java/org/springframework/samples/petclinic/system/WebConfiguration.java:44 localeChangeInterceptor | 1 | 1 |  |
| src/main/java/org/springframework/samples/petclinic/system/WebConfiguration.java:55 addInterceptors | 1 | 0 | yes |
| src/main/java/org/springframework/samples/petclinic/system/WelcomeController.java:25 welcome | 2 | 0 | yes |
| src/main/java/org/springframework/samples/petclinic/vet/Vet.java:52 getSpecialtiesInternal | 1 | 3 |  |
| src/main/java/org/springframework/samples/petclinic/vet/Vet.java:59 getSpecialties | 2 | 2 |  |
| src/main/java/org/springframework/samples/petclinic/vet/Vet.java:66 getNrOfSpecialties | 2 | 1 |  |
| src/main/java/org/springframework/samples/petclinic/vet/Vet.java:70 addSpecialty | 2 | 1 |  |
| src/main/java/org/springframework/samples/petclinic/vet/VetController.java:45 showVetList | 1 | 0 | yes |
| src/main/java/org/springframework/samples/petclinic/vet/VetController.java:56 addPaginationModel | 2 | 0 | yes |
| src/main/java/org/springframework/samples/petclinic/vet/VetController.java:65 findPaginated | 1 | 1 |  |
| src/main/java/org/springframework/samples/petclinic/vet/VetController.java:72 showResourcesVetList | 2 | 0 | yes |
| src/main/java/org/springframework/samples/petclinic/vet/VetRepository.java:44 findAll | 7 | 0 | yes |
| src/main/java/org/springframework/samples/petclinic/vet/VetRepository.java:54 findAll | 7 | 0 | yes |
| src/main/java/org/springframework/samples/petclinic/vet/Vets.java:35 getVetList | 2 | 1 |  |

methods measured: 87; name-level file mentions: 267; resolved call sites: 181; mentions from methods nobody calls: 99
