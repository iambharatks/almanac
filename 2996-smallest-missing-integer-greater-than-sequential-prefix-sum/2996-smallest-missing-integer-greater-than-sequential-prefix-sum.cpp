class Solution {
public:
    int missingInteger(vector<int>& nums) {
        int cnt = 1;
        vector<bool> count(3000);
        for(int &i : nums) count[i] = true;
        for(int i = 1 ; i < size(nums) ; i++){
            if(nums[i] == nums[i-1]+1) cnt++;
            else {
                break;
            }
        }
        int answer = (nums[0] + nums[cnt-1])*cnt/2;
        while(count[answer]) answer++;
        return answer;
    }
};