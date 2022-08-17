
MAKE = make
CXX = g++
CXXFLAGS = --std=c++11 -O3 -I. -fdata-sections -ffunction-sections

TARGETS = \
	run/test-evict-ratio \
	run/test-evict \
	run/test-evict-combine \

OBJECTS = \
	cache/cache.o \
	cache/algorithm.o \
	common/definitions.o \

HEADERS = $(wildcard cache/*.hpp) $(wildcard database/*.hpp) $(wildcard util/*.hpp)

all: $(TARGETS)

$(OBJECTS): %.o:%.cpp $(HEADERS)
	$(CXX) $(CXXFLAGS) -c $< -o $@

$(TARGETS): run/% : test/%.cpp $(OBJECTS)
	$(CXX) $(CXXFLAGS) $^ -Wl,--gc-sections -lpthread -o $@

clean:
	-rm $(TARGETS) $(OBJECTS)
